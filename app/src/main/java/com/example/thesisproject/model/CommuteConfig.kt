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
     * SL Transport API's `direction_code` (0/1/2 per their OpenAPI spec — 0 is
     * "unidentified", 1 and 2 are the two normal directions). NOT the same as
     * GTFS `direction_id` (0/1) — Trafiklab confirms the two systems don't
     * share IDs. Used as a heuristic fallback in direction matching.
     */
    val directionCode: Int? = null,
    /**
     * Name of the stop the user picked (e.g. "Sofia kyrka", "Slussen"). Stored
     * because SL Transport's `site.id` and GTFS's `stop_id` use different
     * schemas, so name-based matching is more reliable for stop-sequence-aware
     * direction lookup. Nullable for configs saved before this field was added.
     */
    val stopName: String? = null
)
