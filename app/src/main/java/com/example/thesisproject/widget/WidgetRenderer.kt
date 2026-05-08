package com.example.thesisproject.widget

import android.content.Context
import android.view.View
import android.widget.RemoteViews
import com.example.thesisproject.R

/**
 * Pure binding from [WidgetCommuteState] (or no-state Dormant) → [RemoteViews].
 * Sub-step 1 of Step 8b: text-only rendering — header line+direction, hero
 * ETA, delta-vs-schedule label, deviation pill. Sub-step 2 will add the
 * Canvas-rendered route line and time-scale-gauge bitmaps.
 *
 * Called from two places: the AppWidgetProvider's `onUpdate` (for cold
 * widget updates from the system) AND the foreground service's per-tick
 * push (for live updates while a commute is active). Both render
 * identically, so behaviour is the same regardless of who triggered it.
 */
object WidgetRenderer {

    fun render(
        context: Context,
        views: RemoteViews,
        state: WidgetCommuteState
    ) {
        // Header — line badge + arrow + direction; scheduled time slot
        // unused in sub-step 1 (no good source for the saved-window's
        // scheduled departure yet — use it in sub-step 2 once we plumb
        // the next departure's `scheduled` time through the deriver).
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

        // Hero ETA — phase-driven text replacements per the design handoff.
        val heroText = when (state.phase) {
            Phase.Dormant -> context.getString(R.string.widget_placeholder)
            Phase.Passed -> "passed"
            Phase.LeaveNow -> "leave now!"
            else -> state.etaMinutes?.let { "$it min" } ?: context.getString(R.string.widget_placeholder)
        }
        views.setTextViewText(R.id.widget_eta, heroText)

        // Delta — hidden when no prediction or in Dormant/Passed states.
        val deltaText = when {
            state.phase == Phase.Dormant -> "off-window"
            state.phase == Phase.Passed -> ""
            state.deltaMinutes == null -> ""
            state.deltaMinutes >= 1 -> "+${state.deltaMinutes}′ late"
            state.deltaMinutes <= -1 -> "${state.deltaMinutes}′ early"
            else -> "on time"
        }
        views.setTextViewText(R.id.widget_delta, deltaText)

        // Deviation pill — visible only when state.deviation != null.
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
        views.setViewVisibility(R.id.widget_deviation, View.GONE)
    }
}
