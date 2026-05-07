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
    val transportMode: String? = null
)
