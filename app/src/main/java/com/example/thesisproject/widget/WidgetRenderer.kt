package com.example.thesisproject.widget

import android.content.Context
import android.view.View
import android.widget.RemoteViews
import com.example.thesisproject.R

/**
 * Pure binding from [WidgetCommuteState] (or no-state Dormant) → [RemoteViews].
 * Sub-step 1 was text-only; sub-step 2 adds Canvas-rendered route-line and
 * time-scale gauge bitmaps via [WidgetBitmapRenderer], plus a stop-labels
 * row (first stop / user's stop bold with star / last stop) per the design
 * handoff.
 *
 * Called from two places: the AppWidgetProvider's `onUpdate` (for cold
 * widget updates from the system) AND the foreground service's per-tick
 * push (for live updates while a commute is active). Both render
 * identically, so behaviour is the same regardless of who triggered it.
 */
object WidgetRenderer {

    /** Render at a generous bitmap resolution that downscales cleanly. */
    private const val ROUTE_LINE_DP_WIDTH = 320
    private const val ROUTE_LINE_DP_HEIGHT = 24
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
        views.setTextViewText(R.id.widget_scheduled, "")

        // --- 2. Route line gauge — Canvas bitmap. ---
        val density = context.resources.displayMetrics.density
        if (state.stopCount >= 2 && state.phase != Phase.Dormant) {
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

        // --- 3. Stop labels ---
        views.setTextViewText(R.id.widget_first_stop, state.firstStopName)
        if (state.stopName.isNotBlank()) {
            val star = context.getString(R.string.widget_user_stop_marker)
            views.setTextViewText(R.id.widget_user_stop, "$star ${state.stopName}")
            views.setViewVisibility(R.id.widget_user_stop, View.VISIBLE)
        } else {
            views.setTextViewText(R.id.widget_user_stop, "")
            views.setViewVisibility(R.id.widget_user_stop, View.GONE)
        }
        views.setTextViewText(R.id.widget_last_stop, state.lastStopName)

        // --- 4. Hero ETA — phase-driven text replacements. ---
        val heroText = when (state.phase) {
            Phase.Dormant -> context.getString(R.string.widget_placeholder)
            Phase.Passed -> "passed"
            Phase.LeaveNow -> "leave now!"
            else -> state.etaMinutes?.let { "$it min" } ?: context.getString(R.string.widget_placeholder)
        }
        views.setTextViewText(R.id.widget_eta, heroText)

        // --- 4b. Time-scale gauge — Canvas bitmap. ---
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

        // --- 4c. Delta label ---
        val deltaText = when {
            state.phase == Phase.Dormant -> "off-window"
            state.phase == Phase.Passed -> ""
            state.deltaMinutes == null -> ""
            state.deltaMinutes >= 1 -> "+${state.deltaMinutes}′ late"
            state.deltaMinutes <= -1 -> "${state.deltaMinutes}′ early"
            else -> "on time"
        }
        views.setTextViewText(R.id.widget_delta, deltaText)

        // --- 5. Deviation pill ---
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
        views.setTextViewText(R.id.widget_last_stop, "")
        views.setViewVisibility(R.id.widget_route, View.INVISIBLE)
        views.setViewVisibility(R.id.widget_time_scale, View.INVISIBLE)
        views.setViewVisibility(R.id.widget_deviation, View.GONE)
    }
}
