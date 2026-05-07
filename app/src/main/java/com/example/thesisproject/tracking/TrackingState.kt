package com.example.thesisproject.tracking

import com.example.thesisproject.model.CommuteConfig
import com.example.thesisproject.model.Deviation
import com.example.thesisproject.model.VehiclePosition

/**
 * Snapshot of what the live-position tracker is doing right now. Observed by
 * MainActivity's verification overlay (and later by Step 6's map markers).
 */
sealed class TrackingState {
    /** Tracker hasn't started yet, or has been stopped. */
    object Idle : TrackingState()

    /**
     * No saved commute's time window contains "now". Tracker is waiting; no
     * network calls being made.
     */
    data class NoActiveCommute(val savedCount: Int) : TrackingState()

    /**
     * Active commute is being polled. [vehicles] is the latest filtered list
     * (already trimmed to the active commute's trip_ids). [lastUpdateMs] is
     * when the most recent fetch completed. [deviations] are SL Deviations API
     * messages already filtered to the active commute's line + stop AND the
     * commute's time window (active-now plus upcoming-during-window).
     */
    data class Polling(
        val activeCommute: CommuteConfig,
        val vehicles: List<VehiclePosition>,
        val lastUpdateMs: Long,
        val deviations: List<Deviation> = emptyList()
    ) : TrackingState()

    /** A fetch failed or the active commute couldn't be matched to the catalog. */
    data class Error(val message: String) : TrackingState()
}
