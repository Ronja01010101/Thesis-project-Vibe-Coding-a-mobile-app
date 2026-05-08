package com.example.thesisproject.widget

/**
 * Render input for the home-screen / lock-screen AppWidget. Computed each
 * polling tick from the active commute's [com.example.thesisproject.tracking.TrackingState]
 * + matched static data (polyline, ordered stops). Pure data — the widget
 * surface (Step 8b) binds these fields directly to its layout views.
 *
 * The route gauge is **windowed**: only the last [visibleStopCount] stops
 * leading up to the user's stop are shown (the user's stop is always the
 * rightmost dot). This is decision-support for catching a bus at a specific
 * stop, not a full-route journey display — anything past the user's stop is
 * out of scope per `project_app_scope.md`.
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
    /**
     * Number of stops shown on the route gauge — at most 5, always ending
     * at the user's stop (rightmost dot). 0 when no static data is loaded
     * yet (Dormant placeholder).
     */
    val visibleStopCount: Int,
    /**
     * Bus position projected into the visible window. Range
     * [0, visibleStopCount - 1]. null when the bus is outside the window
     * (further back than [visibleStopCount] - 1 stops) — caller falls back
     * to [stopsAwayFromUser] for an off-gauge indicator. Also null when no
     * vehicle is currently tracked.
     */
    val visibleBusIndex: Float?,
    /**
     * When [visibleBusIndex] is null because the bus is out of window, this
     * is how many stops back the bus currently is from the user's stop
     * (rounded up — conservative for "should I leave home" decisions).
     * null when the bus is on-gauge or no bus is tracked.
     */
    val stopsAwayFromUser: Int?,
    /**
     * Name of the leftmost visible stop on the gauge — the stop the bus
     * is approaching from in the visible context. Empty when no static
     * data is matched.
     */
    val visibleStartStopName: String,
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
    val phase: Phase
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
