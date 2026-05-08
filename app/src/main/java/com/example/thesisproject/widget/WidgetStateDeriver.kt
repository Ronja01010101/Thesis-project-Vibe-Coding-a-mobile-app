package com.example.thesisproject.widget

import com.example.thesisproject.model.DepartureStatus
import com.example.thesisproject.model.SlDirection
import com.example.thesisproject.model.SlStop
import com.example.thesisproject.model.VehiclePosition
import com.example.thesisproject.tracking.TrackingState
import com.example.thesisproject.util.GeoMath
import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.ceil

/**
 * Pure derivation: TrackingState + matched direction + clock → WidgetCommuteState.
 * No I/O, no Android types — testable as a plain JVM unit.
 *
 * The route gauge is **windowed to the last [WINDOW_SIZE] stops ending at
 * the user's stop** — anything further back the bus's path or after the
 * user's stop is out of scope per `project_app_scope.md` (decision support
 * for catching a specific bus at a specific stop, not a journey planner).
 */
object WidgetStateDeriver {

    /** Maximum stops shown on the route gauge ending at the user's stop. */
    private const val WINDOW_SIZE = 5

    private val CLOCK_FORMATTER = DateTimeFormatter.ofPattern("HH:mm")

    /**
     * Build the widget render state from the current tracker snapshot. Returns
     * null for [TrackingState.Idle] / [TrackingState.Error] — caller substitutes
     * its own placeholder. [TrackingState.NoActiveCommute] returns a Dormant
     * placeholder with the user's stop info empty.
     *
     * [nowMs] is used for vehicle-age computation; defaults to the system
     * wall clock. Caller may pass a fixed value for deterministic tests.
     */
    fun derive(
        state: TrackingState,
        matchedDirection: SlDirection?,
        now: LocalDateTime = LocalDateTime.now(),
        nowMs: Long = System.currentTimeMillis()
    ): WidgetCommuteState? {
        return when (state) {
            is TrackingState.NoActiveCommute -> dormantPlaceholder()
            is TrackingState.Polling -> derivePolling(state, matchedDirection, now, nowMs)
            else -> null
        }
    }

    private fun derivePolling(
        state: TrackingState.Polling,
        direction: SlDirection?,
        now: LocalDateTime,
        nowMs: Long
    ): WidgetCommuteState {
        val cfg = state.activeCommute
        val stops = direction?.stops.orEmpty()

        // Locate the user's stop in the ordered stops list. Match by stopName
        // (CommuteConfig.stopName, populated since Step 5's BUG-009 fix). When
        // missing or unmatchable, default to index 0 — the route-line gauge
        // shows a degraded "user stop = first" but everything else still works.
        val userStopIndex = cfg.stopName?.takeIf { it.isNotBlank() }?.let { name ->
            stops.indexOfFirst { it.name.equals(name, ignoreCase = true) }
        }?.takeIf { it >= 0 } ?: 0

        // Compute the visible window: at most WINDOW_SIZE stops ending at
        // the user's stop. Shorter when user's stop is near the start of
        // the route (e.g. only 2 stops before — gauge shows 3 dots total).
        val windowStart = maxOf(0, userStopIndex - (WINDOW_SIZE - 1))
        val visibleStopCount = if (stops.isEmpty()) 0 else userStopIndex - windowStart + 1
        val visibleStartStopName = stops.getOrNull(windowStart)?.name.orEmpty()

        // Project bus position into the visible window. Out-of-window bus
        // positions yield null + a stops-away count for the off-gauge
        // indicator. Any bus past the user's stop is out of scope (Phase.Passed
        // takes over) and yields null + null.
        val locked = pickLockedVehicle(state.vehicles, stops, userStopIndex)
        val rawBusIndex = locked?.second
        // timestampMs == 0L is the sentinel for "SL didn't report GPS time"
        // (per GtfsRealtimeRepository). When unknown, vehicleAgeSeconds stays
        // null and the renderer hides the GPS-age line — better than showing
        // "0s ago" which would be misleading.
        val vehicleAgeSeconds = locked?.first?.timestampMs
            ?.takeIf { it > 0L }
            ?.let { ((nowMs - it) / 1000L).toInt().coerceAtLeast(0) }
        val visibleBusIndex: Float?
        val stopsAwayFromUser: Int?
        if (rawBusIndex == null || visibleStopCount == 0) {
            visibleBusIndex = null
            stopsAwayFromUser = null
        } else if (rawBusIndex < windowStart) {
            visibleBusIndex = null
            stopsAwayFromUser = ceil(userStopIndex - rawBusIndex.toDouble()).toInt().coerceAtLeast(1)
        } else if (rawBusIndex > userStopIndex) {
            visibleBusIndex = null
            stopsAwayFromUser = null
        } else {
            visibleBusIndex = (rawBusIndex - windowStart).coerceIn(0f, (visibleStopCount - 1).toFloat())
            stopsAwayFromUser = null
        }

        val nextDep = state.nextDeparture
        val etaMin = nextDep?.let { dep ->
            val target = dep.estimatedTime ?: dep.scheduledTime
            Duration.between(now, target).toMinutes().toInt()
        }
        val deltaMin = nextDep?.let { dep ->
            dep.estimatedTime?.let { Duration.between(dep.scheduledTime, it).toMinutes().toInt() }
        }

        // Clock-time strings for the header. Estimated only shown when it
        // differs from scheduled — same value twice would just clutter.
        val scheduledClock = nextDep?.scheduledTime?.format(CLOCK_FORMATTER)
        val estimatedClock = nextDep?.estimatedTime?.format(CLOCK_FORMATTER)
            ?.takeIf { it != scheduledClock }

        val deviationSummary = state.deviations
            .takeIf { it.isNotEmpty() }
            ?.let { devs ->
                val top = devs.maxByOrNull { it.importanceLevel ?: Int.MIN_VALUE }
                WidgetDeviationSummary(
                    header = top?.preferredVariant("sv")?.header.orEmpty(),
                    totalCount = devs.size
                )
            }

        val isCancelled = nextDep?.status == DepartureStatus.CANCELLED
        val phase = computePhase(
            etaMin = etaMin,
            deltaMin = deltaMin,
            hasDeviation = deviationSummary != null,
            isCancelled = isCancelled
        )

        return WidgetCommuteState(
            lineDesignation = cfg.lineDesignation?.takeIf { it.isNotBlank() } ?: cfg.lineId,
            direction = cfg.direction,
            stopName = cfg.stopName.orEmpty(),
            visibleStopCount = visibleStopCount,
            visibleBusIndex = visibleBusIndex,
            stopsAwayFromUser = stopsAwayFromUser,
            visibleStartStopName = visibleStartStopName,
            etaMinutes = etaMin,
            deltaMinutes = deltaMin,
            deviation = deviationSummary,
            phase = phase,
            scheduledClockTime = scheduledClock,
            estimatedClockTime = estimatedClock,
            vehicleAgeSeconds = vehicleAgeSeconds
        )
    }

    /**
     * Pick the most relevant bus to "lock onto" from the matched direction's
     * tracked vehicles, returning both the [VehiclePosition] (so callers can
     * read its [VehiclePosition.timestampMs] for GPS-age display) and its
     * computed busIndex.
     *
     * Heuristic: the vehicle whose busIndex is largest while still
     * ≤ userStopIndex (= the bus that's about to reach the user). If every
     * vehicle is already past, fall back to the smallest busIndex (= the
     * next one that'll come). null when no vehicles tracked.
     */
    private fun pickLockedVehicle(
        vehicles: List<VehiclePosition>,
        stops: List<SlStop>,
        userStopIndex: Int
    ): Pair<VehiclePosition, Float>? {
        if (vehicles.isEmpty() || stops.size < 2) return null
        val perVehicle = vehicles.mapNotNull { v ->
            computeBusIndex(v.lat, v.lon, stops)?.let { v to it }
        }
        if (perVehicle.isEmpty()) return null
        val approaching = perVehicle.filter { it.second <= userStopIndex.toFloat() }
        return if (approaching.isNotEmpty()) {
            approaching.maxByOrNull { it.second }
        } else {
            perVehicle.minByOrNull { it.second }
        }
    }

    /**
     * For a vehicle at (lat, lon), compute its fractional position along the
     * ordered stops list. For each consecutive (s_i, s_{i+1}) segment we
     * project the vehicle and pick whichever segment minimises the
     * distance from the vehicle to its projection point.
     * busIndex = segmentIndex + projectionParameter.
     */
    private fun computeBusIndex(
        lat: Double, lon: Double,
        stops: List<SlStop>
    ): Float? {
        if (stops.size < 2) return null
        var bestSegment = -1
        var bestT = 0.0
        var bestDist = Double.MAX_VALUE
        for (i in 0 until stops.size - 1) {
            val a = stops[i]
            val b = stops[i + 1]
            val t = GeoMath.projectOntoSegment(lat, lon, a.lat, a.lon, b.lat, b.lon)
            val projLat = a.lat + t * (b.lat - a.lat)
            val projLon = a.lon + t * (b.lon - a.lon)
            val d = GeoMath.haversineMeters(lat, lon, projLat, projLon)
            if (d < bestDist) {
                bestDist = d
                bestSegment = i
                bestT = t
            }
        }
        if (bestSegment < 0) return null
        return (bestSegment + bestT).toFloat()
    }

    private fun computePhase(
        etaMin: Int?,
        deltaMin: Int?,
        hasDeviation: Boolean,
        isCancelled: Boolean
    ): Phase {
        if (etaMin == null) return Phase.Dormant
        if (etaMin < 0) return Phase.Passed
        if (isCancelled || hasDeviation) return Phase.Deviation
        if (etaMin <= 3) return Phase.LeaveNow
        if (deltaMin == null) return Phase.OnTime
        return when {
            abs(deltaMin) < 1 -> Phase.OnTime
            deltaMin > 0 -> Phase.Late
            else -> Phase.Early
        }
    }

    private fun dormantPlaceholder(): WidgetCommuteState {
        return WidgetCommuteState(
            lineDesignation = "—",
            direction = "",
            stopName = "",
            visibleStopCount = 0,
            visibleBusIndex = null,
            stopsAwayFromUser = null,
            visibleStartStopName = "",
            etaMinutes = null,
            deltaMinutes = null,
            deviation = null,
            phase = Phase.Dormant,
            scheduledClockTime = null,
            estimatedClockTime = null,
            vehicleAgeSeconds = null
        )
    }
}
