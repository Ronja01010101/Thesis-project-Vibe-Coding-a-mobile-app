package com.example.thesisproject.widget

/**
 * Render input for the home-screen / lock-screen AppWidget. Computed each
 * polling tick from the active commute's [com.example.thesisproject.tracking.TrackingState]
 * + matched static data (polyline, ordered stops). Pure data — the widget
 * surface (Step 8b) binds these fields directly to its layout views.
 *
 * Fields are deliberately primitive / Android-free so this can be carried
 * across processes (foreground service ↔ widget) without a Parcelable
 * serializer beyond what the standard Bundle API already supports.
 */
data class WidgetCommuteState(
    /** Display string for the line, e.g. "57". */
    val lineDesignation: String,
    /** Display string for the direction the user picked, e.g. "Hjorthagen". */
    val direction: String,
    /** Display string for the user's stop, e.g. "Tullgårdsparken". */
    val stopName: String,
    /** Total stop count along the route — for spacing dots on the route line. */
    val stopCount: Int,
    /** Index of the user's stop in the ordered stops list, 0-based. */
    val userStopIndex: Int,
    /**
     * Fractional position of the locked vehicle along the ordered stops list.
     * 0.0 = at first stop, (stopCount - 1) = at last stop. Continuous between
     * stops (e.g. 3.4 = 40% of the way from stop 3 to stop 4). null when no
     * vehicle is currently being tracked.
     */
    val busIndex: Float?,
    /**
     * Minutes until the next predicted departure of the active line+direction
     * at the user's stop. Computed from SL Transport's `expected` (or
     * `scheduled` when prediction unavailable) minus now. Negative = the bus
     * has already passed. null when no upcoming departure could be matched.
     */
    val etaMinutes: Int?,
    /**
     * Predicted-vs-scheduled difference for the next departure at the user's
     * stop, in minutes. Positive = late, negative = early, 0 = on time. null
     * when no prediction is available (only `scheduled` known).
     */
    val deltaMinutes: Int?,
    /** Highest-importance deviation summary, or null if no deviations apply. */
    val deviation: WidgetDeviationSummary?,
    /** Computed render variant — see [Phase] for derivation order. */
    val phase: Phase,
    /**
     * Name of the first stop on the matched direction (origin terminus from
     * the user's perspective). Empty when the static GTFS asset hasn't
     * been matched yet — sub-step-2 stop labels then collapse to a placeholder.
     */
    val firstStopName: String = "",
    /**
     * Name of the last stop on the matched direction (destination terminus,
     * usually = the direction headsign). Empty for the same reason as above.
     */
    val lastStopName: String = ""
)

/** Compact summary of one or more SL deviations affecting the active line. */
data class WidgetDeviationSummary(
    val header: String,
    val totalCount: Int
)

/**
 * Render variants per the design handoff. Order matters for derivation —
 * [com.example.thesisproject.widget.WidgetStateDeriver] checks them in this
 * priority: Dormant > Passed > Deviation > LeaveNow > OnTime > Late > Early.
 * First match wins.
 */
enum class Phase {
    /** Outside the active commute window — em-dash placeholder content. */
    Dormant,
    /** ETA < 0 — the bus has gone past the user's stop. */
    Passed,
    /** A deviation applies, or the next departure is cancelled. */
    Deviation,
    /** ETA <= 3 minutes — show "leave now". */
    LeaveNow,
    /** abs(delta) < 1 — bus running on schedule. */
    OnTime,
    /** delta >= 1 — bus is running late. */
    Late,
    /** delta <= -1 — bus is running early. */
    Early
}
