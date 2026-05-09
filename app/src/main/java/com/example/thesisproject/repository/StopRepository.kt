package com.example.thesisproject.repository

import com.example.thesisproject.api.SlTransportService
import com.example.thesisproject.model.Departure
import com.example.thesisproject.model.DepartureStatus
import com.example.thesisproject.model.Line
import com.example.thesisproject.model.Stop
import com.example.thesisproject.model.StopLineOption
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.time.LocalDateTime

class StopRepository {

    private val api: SlTransportService = Retrofit.Builder()
        .baseUrl("https://transport.integration.sl.se/v1/")
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(SlTransportService::class.java)

    /** Fetches all SL stops. Throws on any network/parse error so callers can surface it. */
    suspend fun getStops(): List<Stop> {
        return api.getSites().map { site ->
            Stop(
                id = site.id.toString(),
                name = site.name,
                lat = site.lat,
                lon = site.lon
            )
        }
    }

    suspend fun getLineOptionsForStop(stopId: String): List<StopLineOption> {
        return try {
            val response = api.getDepartures(stopId)
            response.departures
                .filter { it.directionCode != null && !it.direction.isNullOrBlank() }
                .map { dep ->
                    StopLineOption(
                        line = Line(
                            id = dep.line.id.toString(),
                            name = dep.line.designation,
                            transportMode = dep.line.transportMode
                        ),
                        direction = dep.direction!!,
                        directionCode = dep.directionCode!!
                    )
                }
                .distinctBy { it.line.id to it.directionCode }
                .sortedWith(compareBy({ it.line.transportMode }, { it.line.name }, { it.direction }))
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Step 8a: fetches the next predicted departure at [stopId] for the given
     * (lineId, directionCode), at-or-after [after]. Returns the soonest match
     * with both `scheduled` and (when SL has prediction data) `expected`
     * timestamps populated, so the widget deriver can compute ETA + delta.
     *
     * Returns null when no match could be found — already-passed buses,
     * unparseable timestamps, line/direction not stopping here in the
     * current departures window, or any network/parse failure.
     *
     * IMPORTANT (anti-BUG-009 trap): SL Transport's `journey.id` is NOT the
     * same as GTFS-RT's `trip_id`. We deliberately do NOT use any field on
     * the departure to link it to a specific GTFS-RT vehicle being tracked.
     * The widget surfaces "next bus arriving here" + "vehicles currently
     * on this line" as two independent data streams.
     */
    suspend fun getNextDeparture(
        stopId: String,
        lineId: String,
        directionCode: Int?,
        after: LocalDateTime
    ): Departure? = getUpcomingDepartures(stopId, lineId, directionCode, after, count = 1)
        .firstOrNull()

    /**
     * Step 8a + BUG-028: fetches the next [count] predicted departures at
     * [stopId] for the given (lineId, directionCode), all at-or-after
     * [after], sorted by scheduledTime. The first element is the same as
     * [getNextDeparture]; subsequent elements feed the widget's "Next:"
     * line for high-frequency lines (metro 17, busy bus lines).
     *
     * Same anti-BUG-009 caveat as getNextDeparture: SL Transport's
     * `journey.id` is NOT GTFS-RT's `trip_id`. Departures here cannot be
     * paired one-to-one with vehicles in the GTFS-RT feed.
     */
    suspend fun getUpcomingDepartures(
        stopId: String,
        lineId: String,
        directionCode: Int?,
        after: LocalDateTime,
        count: Int
    ): List<Departure> {
        if (count <= 0) return emptyList()
        val lineIdInt = lineId.toIntOrNull() ?: return emptyList()
        val response = try {
            api.getDepartures(stopId)
        } catch (e: Exception) {
            return emptyList()
        }
        return response.departures
            .asSequence()
            .filter { it.line.id == lineIdInt }
            .filter { directionCode == null || it.directionCode == directionCode }
            .mapNotNull { dep ->
                val scheduled = parseSlDateTime(dep.scheduled) ?: return@mapNotNull null
                if (scheduled.isBefore(after)) return@mapNotNull null
                Departure(
                    stopId = stopId,
                    lineId = lineId,
                    direction = dep.direction.orEmpty(),
                    scheduledTime = scheduled,
                    estimatedTime = parseSlDateTime(dep.expected),
                    status = mapDepartureState(dep.state),
                    dataSource = "sl-transport-departures",
                    fetchedAtMs = System.currentTimeMillis()
                )
            }
            .sortedBy { it.scheduledTime }
            .take(count)
            .toList()
    }

    private fun parseSlDateTime(raw: String?): LocalDateTime? {
        if (raw.isNullOrBlank()) return null
        return try {
            LocalDateTime.parse(raw)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Map SL's `departureStateEnum` to our coarser [DepartureStatus]. SL has
     * no explicit "DELAYED" state — lateness is expressed as the difference
     * between `scheduled` and `expected`, which the widget deriver computes
     * separately. Anything that means "this bus isn't running" collapses to
     * CANCELLED so downstream code only has to check one value.
     */
    private fun mapDepartureState(state: String?): DepartureStatus {
        return when (state?.uppercase()) {
            "CANCELLED", "INHIBITED", "REPLACED", "MISSED", "NOTEXPECTED" -> DepartureStatus.CANCELLED
            "EXPECTED", "ATSTOP", "BOARDING", "BOARDINGCLOSED" -> DepartureStatus.ON_TIME
            else -> DepartureStatus.UNKNOWN
        }
    }
}
