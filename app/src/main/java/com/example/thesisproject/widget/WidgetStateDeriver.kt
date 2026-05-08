package com.example.thesisproject.widget

import com.example.thesisproject.model.DepartureStatus
import com.example.thesisproject.model.SlDirection
import com.example.thesisproject.model.SlStop
import com.example.thesisproject.tracking.TrackingState
import com.example.thesisproject.util.GeoMath
import java.time.Duration
import java.time.LocalDateTime
import kotlin.math.abs

/**
 * Pure derivation: TrackingState + matched direction + clock → WidgetCommuteState.
 * No I/O, no Android types — testable as a plain JVM unit.
 *
 * Step 8a sub-step 1 produces this state and logs it; the widget surface
 * (Step 8b) is where it gets bound to RemoteViews.
 */
object WidgetStateDeriver {

    /**
     * Build the widget render state from the current tracker snapshot. Returns
     * null for [TrackingState.Idle] / [TrackingState.Error] — caller substitutes
     * its own placeholder. [TrackingState.NoActiveCommute] returns a Dormant
     * placeholder with the user's stop info empty.
     */
    fun derive(
        state: TrackingState,
        matchedDirection: SlDirection?,
        now: LocalDateTime = LocalDateTime.now()
    ): WidgetCommuteState? {
        return when (state) {
            is TrackingState.NoActiveCommute -> dormantPlaceholder()
            is TrackingState.Polling -> derivePolling(state, matchedDirection, now)
            else -> null
        }
    }

    private fun derivePolling(
        state: TrackingState.Polling,
        direction: SlDirection?,
        now: LocalDateTime
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

        // Vehicle lock for sub-step 1: pick the vehicle that's at-or-before the
        // user's stop and closest to arriving (= largest busIndex ≤ userStopIndex).
        // If every tracked vehicle is already past the user's stop, fall back
        // to the smallest busIndex (= the next bus that'll eventually reach
        // the user). Sub-step 2 will pin a single vehicle for the whole window.
        val busIndex = pickLockedBusIndex(state.vehicles, stops, userStopIndex)

        val etaMin = state.nextDeparture?.let { dep ->
            val target = dep.estimatedTime ?: dep.scheduledTime
            Duration.between(now, target).toMinutes().toInt()
        }
        val deltaMin = state.nextDeparture?.let { dep ->
            dep.estimatedTime?.let { Duration.between(dep.scheduledTime, it).toMinutes().toInt() }
        }

        val deviationSummary = state.deviations
            .takeIf { it.isNotEmpty() }
            ?.let { devs ->
                val top = devs.maxByOrNull { it.importanceLevel ?: Int.MIN_VALUE }
                WidgetDeviationSummary(
                    header = top?.preferredVariant("sv")?.header.orEmpty(),
                    totalCount = devs.size
                )
            }

        val isCancelled = state.nextDeparture?.status == DepartureStatus.CANCELLED
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
            stopCount = stops.size,
            userStopIndex = userStopIndex,
            busIndex = busIndex,
            etaMinutes = etaMin,
            deltaMinutes = deltaMin,
            deviation = deviationSummary,
            phase = phase,
            firstStopName = stops.firstOrNull()?.name.orEmpty(),
            lastStopName = stops.lastOrNull()?.name.orEmpty()
        )
    }

    private fun pickLockedBusIndex(
        vehicles: List<com.example.thesisproject.model.VehiclePosition>,
        stops: List<SlStop>,
        userStopIndex: Int
    ): Float? {
        if (vehicles.isEmpty() || stops.size < 2) return null
        val perVehicle = vehicles.mapNotNull { v -> computeBusIndex(v.lat, v.lon, stops) }
        if (perVehicle.isEmpty()) return null
        val approaching = perVehicle.filter { it <= userStopIndex.toFloat() }
        return if (approaching.isNotEmpty()) approaching.max() else perVehicle.min()
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
            stopCount = 0,
            userStopIndex = 0,
            busIndex = null,
            etaMinutes = null,
            deltaMinutes = null,
            deviation = null,
            phase = Phase.Dormant
        )
    }
}
