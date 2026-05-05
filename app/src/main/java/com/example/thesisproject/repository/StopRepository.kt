package com.example.thesisproject.repository

import com.example.thesisproject.api.SlTransportService
import com.example.thesisproject.model.Stop
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
                    id = site.gid.toString(),
                    name = site.name,
                    lat = site.lat,
                    lon = site.lon
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
