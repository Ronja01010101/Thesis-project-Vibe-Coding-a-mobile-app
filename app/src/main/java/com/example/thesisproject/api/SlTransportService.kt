package com.example.thesisproject.api

import com.example.thesisproject.api.dto.SiteDto
import retrofit2.http.GET

interface SlTransportService {
    @GET("sites")
    suspend fun getSites(): List<SiteDto>
}
