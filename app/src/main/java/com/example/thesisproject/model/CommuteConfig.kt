package com.example.thesisproject.model

import java.time.LocalTime

data class CommuteConfig(
    val stopId: String,
    val lineId: String,
    val direction: String,
    val timeWindowStart: LocalTime,
    val timeWindowEnd: LocalTime,
    /**
     * Line designation (short name like "4", "55", "T11") — used to match against
     * the GTFS-derived sl-lines.json asset for drawing the line on the map.
     * Nullable for backward compatibility with configs saved before Step 4b
     * (which only stored lineId).
     */
    val lineDesignation: String? = null,
    /** SL transport mode (BUS, METRO, TRAIN, TRAM, SHIP). Nullable for the same reason. */
    val transportMode: String? = null,
    /**
     * GTFS direction_id (0 or 1) as exposed by SL Transport API. When set,
     * preferred over headsign-string matching for picking the right direction
     * within a line — deterministic and immune to SL Transport's direction
     * labels diverging from GTFS final-stop names (BUG-009). Nullable for
     * configs saved before Step 5's BUG-009 fix.
     */
    val directionCode: Int? = null
)
