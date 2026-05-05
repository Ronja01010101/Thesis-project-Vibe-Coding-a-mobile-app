package com.example.thesisproject.model

import java.time.LocalDateTime

enum class DepartureStatus {
    ON_TIME,
    DELAYED,
    CANCELLED,
    UNKNOWN
}

data class Departure(
    val stopId: String,
    val lineId: String,
    val direction: String,
    val scheduledTime: LocalDateTime,
    val estimatedTime: LocalDateTime?,
    val status: DepartureStatus,
    val dataSource: String,
    val fetchedAtMs: Long
)
