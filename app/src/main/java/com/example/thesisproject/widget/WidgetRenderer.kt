package com.example.thesisproject.widget

import android.content.Context
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews
import com.example.thesisproject.R

/**
 * Pure binding from [WidgetCommuteState] (or no-state Dormant) → [RemoteViews].
 * Sub-step 2.7 visual revision: descriptive captions instead of cryptic
 * suffixes — "Estimated arrival" caption above the hero number, dedicated
 * "Bus is N stops away" line when out of window, dedicated "Bus position
 * Xs ago" GPS-age line. Off-gauge bitmap chip removed in favour of the
 * dedicated text.
 *
 * Called from two places: the AppWidgetProvider's `onUpdate` (for cold
 * widget updates from the system) AND the foreground service's per-tick
 * push (for live updates while a commute is active). Both render
 * identically, so behaviour is the same regardless of who triggered it.
 */
object WidgetRenderer {

    /** Render at a generous bitmap resolution that downscales cleanly. */
    private const val ROUTE_LINE_DP_WIDTH = 320
    private const val ROUTE_LINE_DP_HEIGHT = 22
    private const val TIME_SCALE_DP_WIDTH = 80
    private const val TIME_SCALE_DP_HEIGHT = 20

    fun render(
        context: Context,
        views: RemoteViews,
        state: WidgetCommuteState
    ) {
        // --- 1. Header ---
        views.setTextViewText(R.id.widget_line_badge, state.lineDesignation)
        views.setTextViewText(
            R.id.widget_destination,
            buildString {
                append("→ ")
                append(state.direction.ifBlank { context.getString(R.string.widget_dormant_destination).removePrefix("→ ") })
                if (state.stopName.isNotBlank()) {
                    append(" · from ")
                    append(state.stopName)
                }
            }
        )
        // Header right slot: scheduled clock time, plus an arrow + estimated
        // when the prediction differs (e.g. "07:54 → 07:56" when 2 min late).
        val scheduledHeader = when {
            state.scheduledClockTime != null && state.estimatedClockTime != null ->
                "${state.scheduledClockTime} → ${state.estimatedClockTime}"
            state.scheduledClockTime != null -> state.scheduledClockTime
            else -> ""
        }
        views.setTextViewText(R.id.widget_scheduled, scheduledHeader)

        // --- 2. Route line gauge — Canvas bitmap. ---
        val density = context.resources.displayMetrics.density
        if (state.visibleStopCount >= 1 && state.phase != Phase.Dormant) {
            val route = WidgetBitmapRenderer.renderRouteLine(
                context = context,
                widthPx = (ROUTE_LINE_DP_WIDTH * density).toInt(),
                heightPx = (ROUTE_LINE_DP_HEIGHT * density).toInt(),
                state = state
            )
            views.setImageViewBitmap(R.id.widget_route, route)
            views.setViewVisibility(R.id.widget_route, View.VISIBLE)
        } else {
            views.setViewVisibility(R.id.widget_route, View.INVISIBLE)
        }

        // --- 3. Stop labels (leftmost-visible + ★ user stop) ---
        views.setTextViewText(R.id.widget_first_stop, state.visibleStartStopName)
        if (state.stopName.isNotBlank()) {
            val star = context.getString(R.string.widget_user_stop_marker)
            views.setTextViewText(R.id.widget_user_stop, "$star ${state.stopName}")
            views.setViewVisibility(R.id.widget_user_stop, View.VISIBLE)
        } else {
            views.setTextViewText(R.id.widget_user_stop, "")
            views.setViewVisibility(R.id.widget_user_stop, View.GONE)
        }

        // --- 4. Stops-away caption — only when bus is out of the visible window.
        // Caveat preserved from BUG-021: this is from GTFS-RT VehiclePositions
        // (live lat/lons) while the hero ETA is from SL Departures (prediction
        // model). Trafiklab support has confirmed `journey.id` ≠ GTFS-RT
        // `trip_id`, so the rendered bus is not guaranteed to be the same
        // physical vehicle SL is predicting for the user's stop. In practice
        // it usually is (next-arriving = closest-visible), but mismatches can
        // happen on routes where buses overlap or run close together.
        val stopsAway = state.stopsAwayFromUser
        if (stopsAway != null && state.phase != Phase.Passed && state.phase != Phase.Dormant) {
            views.setTextViewText(
                R.id.widget_stops_away,
                "$stopsAway stop${if (stopsAway == 1) "" else "s"} away"
            )
            views.setViewVisibility(R.id.widget_stops_away, View.VISIBLE)
        } else {
            views.setViewVisibility(R.id.widget_stops_away, View.GONE)
        }

        // --- 5. Hero block (caption + ETA stacked). ---
        // The "Estimated arrival" caption is a static layout string; we only
        // need to fill the ETA text + show/hide the caption depending on phase.
        val heroText = when (state.phase) {
            Phase.Dormant -> context.getString(R.string.widget_placeholder)
            Phase.Passed -> "passed"
            Phase.LeaveNow -> "leave now!"
            else -> state.etaMinutes?.let { "$it min" } ?: context.getString(R.string.widget_placeholder)
        }
        views.setTextViewText(R.id.widget_eta, heroText)
        // Hide caption for Dormant / Passed since the hero text is the answer
        // by itself (no "estimated arrival: passed" — just "passed").
        val heroCaptionVisible = state.phase != Phase.Dormant && state.phase != Phase.Passed
        views.setViewVisibility(
            R.id.widget_hero_label,
            if (heroCaptionVisible) View.VISIBLE else View.GONE
        )

        // --- 5b. Time-scale gauge — Canvas bitmap. ---
        if (state.phase != Phase.Dormant && state.phase != Phase.Passed) {
            val scale = WidgetBitmapRenderer.renderTimeScale(
                context = context,
                widthPx = (TIME_SCALE_DP_WIDTH * density).toInt(),
                heightPx = (TIME_SCALE_DP_HEIGHT * density).toInt(),
                state = state
            )
            views.setImageViewBitmap(R.id.widget_time_scale, scale)
            views.setViewVisibility(R.id.widget_time_scale, View.VISIBLE)
        } else {
            views.setViewVisibility(R.id.widget_time_scale, View.INVISIBLE)
        }

        // --- 5c. Delta label (no GPS-age suffix anymore — that's its own row). ---
        val deltaText = when {
            state.phase == Phase.Dormant -> "off-window"
            state.phase == Phase.Passed -> ""
            state.deltaMinutes == null -> ""
            state.deltaMinutes >= 1 -> "+${state.deltaMinutes}′ late"
            state.deltaMinutes <= -1 -> "${state.deltaMinutes}′ early"
            else -> "on time"
        }
        views.setTextViewText(R.id.widget_delta, deltaText)

        // --- 6. GPS-age caption — Chronometer auto-ticks "Updated MM:SS ago"
        // every second inside the launcher's process. We only set the base
        // when we have a real GPS timestamp from SL; otherwise the line is
        // hidden. Chronometer.base is in elapsed-realtime-since-boot, not
        // epoch ms, so we convert: base = elapsedRealtime - (now - tsEpoch).
        val tsEpochMs = state.vehicleTimestampMs
            ?.takeIf { state.phase != Phase.Dormant && state.phase != Phase.Passed }
        if (tsEpochMs != null) {
            val nowEpochMs = System.currentTimeMillis()
            val nowElapsed = SystemClock.elapsedRealtime()
            val elapsedBase = nowElapsed - (nowEpochMs - tsEpochMs)
            views.setChronometer(
                R.id.widget_gps_age,
                elapsedBase,
                context.getString(R.string.widget_gps_age_format),
                /* started = */ true
            )
            views.setViewVisibility(R.id.widget_gps_age, View.VISIBLE)
        } else {
            views.setViewVisibility(R.id.widget_gps_age, View.GONE)
        }

        // --- 7. Deviation pill ---
        val deviation = state.deviation
        if (deviation != null) {
            val text = if (deviation.totalCount > 1) {
                "${deviation.header} (+${deviation.totalCount - 1})"
            } else {
                deviation.header
            }
            views.setTextViewText(R.id.widget_deviation, text)
            views.setViewVisibility(R.id.widget_deviation, View.VISIBLE)
        } else {
            views.setViewVisibility(R.id.widget_deviation, View.GONE)
        }
    }

    /**
     * Render the widget for a Dormant commute — the bound CommuteConfig is
     * not currently within its time window, so we just show the placeholder
     * shape. Used both at install time and for widgets bound to commutes
     * other than the currently-active one.
     */
    fun renderDormant(context: Context, views: RemoteViews, lineLabel: String?, directionLabel: String?) {
        views.setTextViewText(R.id.widget_line_badge, lineLabel?.takeIf { it.isNotBlank() } ?: context.getString(R.string.widget_placeholder))
        views.setTextViewText(
            R.id.widget_destination,
            directionLabel?.takeIf { it.isNotBlank() }?.let { "→ $it" }
                ?: context.getString(R.string.widget_dormant_destination)
        )
        views.setTextViewText(R.id.widget_scheduled, "")
        views.setTextViewText(R.id.widget_eta, context.getString(R.string.widget_placeholder))
        views.setTextViewText(R.id.widget_delta, "off-window")
        views.setTextViewText(R.id.widget_first_stop, "")
        views.setTextViewText(R.id.widget_user_stop, "")
        views.setViewVisibility(R.id.widget_user_stop, View.GONE)
        views.setViewVisibility(R.id.widget_route, View.INVISIBLE)
        views.setViewVisibility(R.id.widget_time_scale, View.INVISIBLE)
        views.setViewVisibility(R.id.widget_hero_label, View.GONE)
        views.setViewVisibility(R.id.widget_stops_away, View.GONE)
        views.setViewVisibility(R.id.widget_gps_age, View.GONE)
        views.setViewVisibility(R.id.widget_deviation, View.GONE)
    }
}
