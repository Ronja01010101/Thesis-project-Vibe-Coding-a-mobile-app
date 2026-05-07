package com.example.thesisproject.model

/**
 * Top-level structure of the bundled `sl-lines.json` asset that the
 * `extractGtfs` Gradle task produces from the SL GTFS Regional Static feed.
 * Field names match the JSON exactly so Gson can map straight onto these
 * data classes with no configuration.
 */
data class SlLineCatalog(
    val generatedAt: String = "",
    val feedSource: String = "",
    val lineCount: Int = 0,
    val lines: List<SlLineEntry> = emptyList()
)

data class SlLineEntry(
    val lineDesignation: String = "",
    val routeId: String = "",
    val routeType: Int = -1,
    val directions: List<SlDirection> = emptyList()
)

data class SlDirection(
    val directionId: Int = 0,
    val headsign: String = "",
    val stops: List<SlStop> = emptyList(),
    /** Each polyline point is [lat, lon]. Coordinates rounded to 5 decimals (~1m). */
    val polyline: List<List<Double>> = emptyList()
)

data class SlStop(
    val id: String = "",
    val name: String = "",
    val lat: Double = 0.0,
    val lon: Double = 0.0
)
