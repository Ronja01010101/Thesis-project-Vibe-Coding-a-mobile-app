package com.example.thesisproject.api.dto

import com.google.gson.annotations.SerializedName

data class DeparturesResponse(
    val departures: List<DepartureDto>
)

data class DepartureDto(
    val line: LineDto,
    val direction: String?,
    @SerializedName("direction_code") val directionCode: Int?,
    val destination: String?
)

data class LineDto(
    val id: Int,
    val designation: String,
    @SerializedName("transport_mode") val transportMode: String
)
