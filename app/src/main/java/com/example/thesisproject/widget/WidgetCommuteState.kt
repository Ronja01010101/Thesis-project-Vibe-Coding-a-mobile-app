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
    val phase: Phase,
    /**
     * Scheduled clock time of the next departure at the user's stop,
     * formatted "HH:mm" (24-hour, Stockholm local). null when no upcoming
     * departure could be matched.
     */
    val scheduledClockTime: String? = null,
    /**
     * Estimated clock time, formatted "HH:mm". null when SL has no live
     * prediction yet OR the prediction equals scheduled (= on time, no
     * point cluttering the header with the same value twice).
     */
    val estimatedClockTime: String? = null,
    /**
     * Epoch-ms timestamp of the locked vehicle's last GPS fix (from the
     * GTFS-RT feed's `vehicle.timestamp`, NOT our poll time — SL's feed
     * lags the actual GPS by 30–90s per BUG-012). null when no vehicle is
     * tracked OR when SL omitted the timestamp field for that vehicle
     * (per BUG-016 v2 the repository keeps 0L as a "unknown" sentinel
     * which the deriver translates to null here).
     *
     * Used by the widget renderer to drive a Chronometer that auto-ticks
     * "Updated MM:SS ago" inside the launcher's process — that way the
     * displayed age progresses every second between widget pushes,
     * instead of sitting frozen between the foreground service's 20-s
     * refreshes.
     */
    val vehicleTimestampMs: Long? = null,
    /**
     * BUG-028: window-local indices for ALL non-locked vehicles whose
     * busIndex falls inside [windowStart .. userStopIndex]. Drawn as
     * smaller, neutral-coloured markers on the gauge so the user sees
     * "yes, there's another bus 4 stops behind the one we're tracking".
     * The locked vehicle's index is in [visibleBusIndex] and is NOT in
     * this list — drawn separately with phase colour and line text.
     */
    val additionalBusIndices: List<Float> = emptyList(),
    /**
     * BUG-028: clock-time strings ("HH:mm") for upcoming departures AFTER
     * the hero, fed into the widget's "Next:" line. Empty when SL has no
     * additional upcoming departures (low-frequency lines, or end of
     * service day). Intentionally excludes the hero's departure — that's
     * already shown via [scheduledClockTime] / [estimatedClockTime] +
     * [etaMinutes].
     */
    val nextDepartureClockTimes: List<String> = emptyList(),
    /**
     * BUG-030: epoch-ms of the last successful service poll (carried from
     * `TrackingState.Polling.lastUpdateMs`). Drives the widget's "Updated
     * MM:SS ago" Chronometer. Same semantic as the in-app `live_status`
     * banner so the two surfaces agree on what "updated" means. null when
     * no successful poll has happened yet (cold start).
     *
     * Replaces [vehicleTimestampMs] as the Chronometer's base — that field
     * was tied to SL's GPS-report time which lags the actual feed by
     * 30-90s (per BUG-012), making the widget timer look broken to users
     * comparing it to the app's live_status.
     */
    val lastUpdateMs: Long? = null
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
