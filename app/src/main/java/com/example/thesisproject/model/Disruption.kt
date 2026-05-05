package com.example.thesisproject.model

enum class DisruptionSeverity {
    INFO,
    WARNING,
    CRITICAL
}

data class Disruption(
    val id: String,
    val lineId: String?,
    val stopId: String?,
    val description: String,
    val severity: DisruptionSeverity,
    val fetchedAtMs: Long
)
