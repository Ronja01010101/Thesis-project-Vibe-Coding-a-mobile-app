package com.example.thesisproject.api.dto

import com.google.gson.annotations.SerializedName

data class DeparturesResponse(
    val departures: List<DepartureDto>
)

data class DepartureDto(
    val line: LineDto,
    val direction: String?,
    @SerializedName("direction_code") val directionCode: Int?,
    val destination: String?,
    /**
     * Officially scheduled departure time at this stop. Per Trafiklab's SL
     * Transport OpenAPI spec: ISO-8601 local datetime in Stockholm time, no
     * timezone offset (e.g. "2024-01-01T07:54:00"). Documented as required
     * but kept nullable defensively in case the API ever omits it.
     */
    val scheduled: String?,
    /**
     * Real-time predicted departure time at this stop. Same format as
     * [scheduled]. Optional — absent when SL has no live prediction yet.
     */
    val expected: String?,
    /**
     * Departure state per SL's `departureStateEnum`. Common values:
     * NOTEXPECTED, NOTCALLED, EXPECTED, CANCELLED, INHIBITED, ATSTOP,
     * BOARDING, BOARDINGCLOSED, DEPARTED, PASSED, MISSED, REPLACED,
     * ASSUMEDDEPARTED.
     */
    val state: String?
)

data class LineDto(
    val id: Int,
    val designation: String,
    @SerializedName("transport_mode") val transportMode: String
)
