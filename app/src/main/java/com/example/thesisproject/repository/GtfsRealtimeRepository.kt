package com.example.thesisproject.repository

import android.util.Log
import com.example.thesisproject.model.DataQuality
import com.example.thesisproject.model.VehiclePosition
import com.google.transit.realtime.GtfsRealtime.FeedMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Fetches and parses the SL GTFS Regional Realtime VehiclePositions feed.
 *
 * The feed is a single protobuf blob containing every SL vehicle currently
 * broadcasting position (~1500–2000 entities, ~170 KB gzipped). We filter
 * client-side to vehicles whose `trip.trip_id` is in [tripIds] — typically
 * the trip_ids for the active commute's matched (route, direction).
 *
 * Why filter by trip_id rather than route_id: SL's realtime feed populates
 * `trip.trip_id` for ~98% of entities but `trip.route_id` for only ~1%.
 * Confirmed empirically by `./gradlew smokeTestRealtime` on 2026-05-07.
 */
class GtfsRealtimeRepository(
    private val client: OkHttpClient = defaultClient()
) {

    suspend fun fetchVehiclePositions(
        apiKey: String,
        tripIds: Set<String>,
        lineDesignation: String,
        direction: String
    ): List<VehiclePosition> = withContext(Dispatchers.IO) {
        require(apiKey.isNotBlank()) { "GTFS_REALTIME_KEY missing — cannot fetch realtime data" }
        if (tripIds.isEmpty()) return@withContext emptyList()

        val request = Request.Builder()
            .url("$ENDPOINT?key=$apiKey")
            .header("Accept", "application/octet-stream, */*")
            .header("User-Agent", "thesis-project-android/1.0")
            // Note: we don't set Accept-Encoding manually. OkHttp adds
            // `Accept-Encoding: gzip` automatically and decompresses the
            // response body transparently — but only when the header is
            // not already set on the request. The Trafiklab endpoint
            // requires gzip/deflate to be advertised; OkHttp's default
            // behaviour satisfies that requirement.
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                error("Realtime API returned HTTP ${response.code()}")
            }
            val bytes = response.body()?.bytes() ?: error("Empty realtime response body")
            val feed = FeedMessage.parseFrom(bytes)
            val nowMs = System.currentTimeMillis()

            val totalLiveTripIds = feed.entityList.count { it.hasVehicle() && it.vehicle.hasTrip() && it.vehicle.trip.hasTripId() }
            val matchingLiveTripIds = feed.entityList.count {
                it.hasVehicle() && it.vehicle.hasTrip() && it.vehicle.trip.tripId in tripIds
            }
            Log.d(TAG, "fetched feed: ${feed.entityCount} entities, $totalLiveTripIds with trip_id, $matchingLiveTripIds match our ${tripIds.size}-tripId filter (line $lineDesignation, dir $direction)")

            feed.entityList.mapNotNull { entity ->
                if (!entity.hasVehicle()) return@mapNotNull null
                val v = entity.vehicle
                if (!v.hasPosition() || !v.hasTrip()) return@mapNotNull null
                val tripId = v.trip.tripId
                if (tripId.isNullOrBlank() || tripId !in tripIds) return@mapNotNull null

                // SL's GTFS-RT feed often omits vehicle.timestamp. We keep
                // 0L as a sentinel for "unknown GPS time" so widget code can
                // suppress the GPS-age display rather than incorrectly showing
                // "0s ago". Quality calculation still uses the previous
                // fallback so the existing LIVE/UNCERTAIN visualisation logic
                // (Steps 5/6) doesn't change behaviour for vehicles without
                // a reported GPS timestamp.
                val gpsTimestampMs = if (v.timestamp != 0L) v.timestamp * 1000L else 0L
                val qualityBasisMs = if (gpsTimestampMs > 0L) gpsTimestampMs else nowMs
                val ageMs = nowMs - qualityBasisMs

                VehiclePosition(
                    vehicleId = entity.id,
                    tripId = tripId,
                    lineId = lineDesignation,
                    direction = direction,
                    lat = v.position.latitude.toDouble(),
                    lon = v.position.longitude.toDouble(),
                    bearing = if (v.position.hasBearing()) v.position.bearing else null,
                    timestampMs = gpsTimestampMs,
                    dataSource = DATA_SOURCE,
                    quality = if (ageMs in 0..STALE_THRESHOLD_MS) DataQuality.LIVE else DataQuality.UNCERTAIN
                )
            }
        }
    }

    companion object {
        private const val TAG = "LiveTracking"
        private const val ENDPOINT = "https://opendata.samtrafiken.se/gtfs-rt/sl/VehiclePositions.pb"
        private const val DATA_SOURCE = "GTFS-RT SL"
        // Position older than 60s is flagged UNCERTAIN. Tunable later when
        // we have more empirical data on how often the feed lags reality.
        private const val STALE_THRESHOLD_MS = 60_000L

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}
