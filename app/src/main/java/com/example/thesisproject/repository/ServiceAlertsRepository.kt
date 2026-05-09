package com.example.thesisproject.repository

import android.util.Log
import com.example.thesisproject.model.TripAlert
import com.google.transit.realtime.GtfsRealtime.FeedMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Fetches and parses the SL GTFS Realtime ServiceAlerts feed for
 * trip-level alerts that affect specific buses we're tracking.
 *
 * Empirical findings from `./gradlew smokeTestServiceAlerts` (2026-05-09):
 * - 208 alerts, 671 informed_entity selectors at the time of the spike
 * - ~15% of selectors carry `trip.trip_id` (the rest are stop/route level)
 * - trip_ids match the same scheme as VehiclePositions, so direct filter
 *   against tracked trip_ids works without any bridge
 *
 * We keep ONLY alerts that carry at least one trip_id selector — the value
 * proposition of this feed (over the SL Deviations API we already use) is
 * trip-level granularity. Stop / route / route_type level alerts are left
 * to the SL Deviations integration; running both prevents duplicate
 * surfacing of the same disruption.
 */
class ServiceAlertsRepository(
    private val client: OkHttpClient = defaultClient()
) {

    suspend fun fetchTripAlerts(apiKey: String): List<TripAlert> = withContext(Dispatchers.IO) {
        require(apiKey.isNotBlank()) { "GTFS_REALTIME_KEY missing — cannot fetch service alerts" }

        val request = Request.Builder()
            .url("$ENDPOINT?key=$apiKey")
            .header("Accept", "application/octet-stream, */*")
            .header("User-Agent", "thesis-project-android/1.0")
            // OkHttp adds Accept-Encoding: gzip automatically and
            // decompresses transparently when we don't set it manually —
            // matches the same pattern as GtfsRealtimeRepository.
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                error("ServiceAlerts API returned HTTP ${response.code()}")
            }
            val bytes = response.body()?.bytes() ?: error("Empty service-alerts response body")
            val feed = FeedMessage.parseFrom(bytes)

            val alerts = feed.entityList
                .filter { it.hasAlert() }
                .mapNotNull { entity ->
                    val alert = entity.alert
                    val tripIds = alert.informedEntityList
                        .asSequence()
                        .filter { it.hasTrip() && it.trip.hasTripId() }
                        .map { it.trip.tripId }
                        .filter { it.isNotBlank() }
                        .toSet()
                    if (tripIds.isEmpty()) return@mapNotNull null
                    val header = preferredTranslation(alert, preferLanguage = "sv") { it.headerText }
                    val description = preferredTranslation(alert, preferLanguage = "sv") { it.descriptionText }
                    TripAlert(
                        id = entity.id,
                        header = header.orEmpty(),
                        description = description.orEmpty(),
                        cause = if (alert.hasCause()) alert.cause.toString() else null,
                        effect = if (alert.hasEffect()) alert.effect.toString() else null,
                        tripIds = tripIds
                    )
                }
            Log.d(TAG, "fetched ${feed.entityList.count { it.hasAlert() }} alerts, ${alerts.size} carry trip_ids")
            alerts
        }
    }

    /**
     * GTFS-RT TranslatedString picker. Returns the [preferLanguage] match if
     * present, else the first translation, else null. The proto type is
     * accessed via a getter lambda because GTFS-RT has multiple
     * TranslatedString fields (`headerText`, `descriptionText`,
     * `tts_header_text`, `url`, etc.) and we only want a couple.
     */
    private fun preferredTranslation(
        alert: com.google.transit.realtime.GtfsRealtime.Alert,
        preferLanguage: String,
        get: (com.google.transit.realtime.GtfsRealtime.Alert) -> com.google.transit.realtime.GtfsRealtime.TranslatedString
    ): String? {
        val ts = get(alert)
        if (ts.translationCount == 0) return null
        val preferred = (0 until ts.translationCount)
            .map { ts.getTranslation(it) }
            .firstOrNull { it.hasLanguage() && it.language.equals(preferLanguage, ignoreCase = true) }
        return (preferred ?: ts.getTranslation(0)).text
    }

    companion object {
        private const val TAG = "ServiceAlerts"
        private const val ENDPOINT = "https://opendata.samtrafiken.se/gtfs-rt/sl/ServiceAlerts.pb"

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}
