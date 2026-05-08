package com.example.thesisproject.widget

import com.example.thesisproject.model.CommuteConfig

/**
 * Stable string identity for a saved [CommuteConfig], used to compare which
 * commute a given widget is bound to vs. which commute is currently being
 * tracked by the foreground service. Identity is the tuple
 * `stopId|lineId|direction|timeWindowStart` — captures the user's intent
 * (specific line+direction at a specific stop in a specific window) without
 * being sensitive to optional fields like `lineDesignation`/`stopName`/etc
 * that may be missing on legacy saves.
 */
object WidgetCommuteIdentity {
    fun from(config: CommuteConfig): String =
        "${config.stopId}|${config.lineId}|${config.direction}|${config.timeWindowStart}"
}
