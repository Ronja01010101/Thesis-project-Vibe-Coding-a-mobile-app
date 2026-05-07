package com.example.thesisproject.model

enum class DataQuality {
    LIVE,       // fresh position, on expected route
    UNCERTAIN,  // position is old, missing, or off-route
    MISSING     // no position data available at all
}

data class VehiclePosition(
    val vehicleId: String,
    val tripId: String,
    val lineId: String,
    val direction: String,
    val lat: Double,
    val lon: Double,
    val bearing: Float?,
    val timestampMs: Long,
    val dataSource: String,
    val quality: DataQuality
)
