package com.example.thesisproject.api

import com.example.thesisproject.api.dto.DeparturesResponse
import com.example.thesisproject.api.dto.SiteDto
import retrofit2.http.GET
import retrofit2.http.Path

interface SlTransportService {

    @GET("sites")
    suspend fun getSites(): List<SiteDto>

    @GET("sites/{siteId}/departures")
    suspend fun getDepartures(@Path("siteId") siteId: String): DeparturesResponse
}
