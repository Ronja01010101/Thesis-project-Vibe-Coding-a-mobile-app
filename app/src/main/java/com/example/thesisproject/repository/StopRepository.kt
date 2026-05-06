package com.example.thesisproject.repository

import com.example.thesisproject.api.SlTransportService
import com.example.thesisproject.model.Line
import com.example.thesisproject.model.Stop
import com.example.thesisproject.model.StopLineOption
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class StopRepository {

    private val api: SlTransportService = Retrofit.Builder()
        .baseUrl("https://transport.integration.sl.se/v1/")
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(SlTransportService::class.java)

    suspend fun getStops(): List<Stop> {
        return try {
            api.getSites().map { site ->
                Stop(
                    id = site.id.toString(),
                    name = site.name,
                    lat = site.lat,
                    lon = site.lon
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun getLineOptionsForStop(stopId: String): List<StopLineOption> {
        return try {
            val response = api.getDepartures(stopId)
            response.departures
                .filter { it.directionCode != null && !it.direction.isNullOrBlank() }
                .map { dep ->
                    StopLineOption(
                        line = Line(
                            id = dep.line.id.toString(),
                            name = dep.line.designation,
                            transportMode = dep.line.transportMode
                        ),
                        direction = dep.direction!!,
                        directionCode = dep.directionCode!!
                    )
                }
                .distinctBy { it.line.id to it.directionCode }
                .sortedWith(compareBy({ it.line.transportMode }, { it.line.name }, { it.direction }))
        } catch (e: Exception) {
            emptyList()
        }
    }
}
