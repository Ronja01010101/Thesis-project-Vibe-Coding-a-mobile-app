package com.example.thesisproject.model

data class TransportState(
    val commuteConfig: CommuteConfig,
    val vehicle: VehiclePosition?,
    val nextDeparture: Departure?,
    val disruptions: List<Disruption>,
    val lastUpdatedMs: Long,
    val dataSource: String
)
