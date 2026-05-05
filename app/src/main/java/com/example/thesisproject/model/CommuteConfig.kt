package com.example.thesisproject.model

import java.time.LocalTime

data class CommuteConfig(
    val stopId: String,
    val lineId: String,
    val direction: String,
    val timeWindowStart: LocalTime,
    val timeWindowEnd: LocalTime
)
