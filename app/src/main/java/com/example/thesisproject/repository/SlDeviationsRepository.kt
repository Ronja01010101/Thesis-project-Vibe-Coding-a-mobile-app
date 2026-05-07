package com.example.thesisproject.repository

import android.util.Log
import com.example.thesisproject.model.Deviation
import com.example.thesisproject.model.MessageVariant
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import java.util.concurrent.TimeUnit

/**
 * Fetches deviations (disruptions) from the SL Deviations API.
 *
 * Endpoint: `https://deviations.integration.sl.se/v1/messages`
 *
 * No API key required (per docs). Polling cadence: max once per minute (per docs;
 * we use 60 s in [LivePositionTracker]). Conditional GET via `If-None-Match`/`ETag`
 * is supported by the server — when sending the previous response's ETag, the
 * server returns 304 with no body if nothing has changed, which we surface as
 * [FetchResult.NotModified] so callers can keep their cached list.
 *
 * Server-side filtering: we pass `?line=<id>&future=true` so the server only
 * returns deviations affecting the user's active commute's line. The
 * `future=true` flag includes upcoming deviations; client-side time-window
 * filtering happens in [LivePositionTracker] (we want active-now plus upcoming
 * during the commute window, not weeks-out planned work).
 *
 * **Why no `site` filter** — first runtime test (2026-05-07, Step 7) showed
 * the API's `&site=<id>` param requires the deviation's `scope.stop_areas`
 * to literally include the user's stop id. SL writes deviations per affected
 * stop (e.g. "Jungfrugatan stop relocated") even when the deviation is part
 * of a route-wide situation. Filtering by site dropped relevant deviations
 * affecting a different stop on the user's line. False negatives (missing a
 * deviation that affects the user's bus showing up at their stop) are
 * dangerous; false positives (a deviation a few stops away that may or may
 * not affect them) are mild noise the user can read past in the details.
 * Per project scope memory: app's job is pre-trip decision support, not
 * journey planning — broad surfacing + user judgement is the right tradeoff.
 */
class SlDeviationsRepository(
    private val client: OkHttpClient = defaultClient(),
    private val gson: Gson = Gson()
) {

    sealed class FetchResult {
        /** Server returned 304 — caller should keep its cached list and ETag. */
        object NotModified : FetchResult()
        /** Server returned 200 — fresh list and (optional) new ETag. */
        data class Modified(val deviations: List<Deviation>, val etag: String?) : FetchResult()
    }

    suspend fun fetchDeviations(
        lineId: Int,
        etag: String? = null,
        includeFuture: Boolean = true
    ): FetchResult = withContext(Dispatchers.IO) {
        val url = buildString {
            append(ENDPOINT)
            append("?line=").append(lineId)
            if (includeFuture) append("&future=true")
        }
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", "thesis-project-android/1.0")
            .apply { if (!etag.isNullOrBlank()) header("If-None-Match", etag) }
            .build()

        client.newCall(request).execute().use { response ->
            if (response.code() == 304) {
                Log.d(TAG, "304 not-modified line=$lineId")
                return@withContext FetchResult.NotModified
            }
            if (!response.isSuccessful) {
                error("Deviations API returned HTTP ${response.code()}")
            }
            val body = response.body()?.string().orEmpty()
            val newEtag = response.header("ETag")
            val type = object : TypeToken<List<DeviationDto>>() {}.type
            val dtos: List<DeviationDto> = try {
                gson.fromJson(body, type) ?: emptyList()
            } catch (e: Exception) {
                Log.w(TAG, "JSON parse failed", e)
                emptyList()
            }
            val deviations = dtos.mapNotNull { it.toDomain() }
            Log.d(TAG, "fetched ${deviations.size} deviations (raw=${dtos.size}) line=$lineId etag=$newEtag")
            FetchResult.Modified(deviations, newEtag)
        }
    }

    private fun DeviationDto.toDomain(): Deviation? {
        val versionInt = version ?: return null
        val from = publish?.from?.let(::parseInstant) ?: return null
        val variants = messageVariants?.mapNotNull { v ->
            val header = v.header?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val details = v.details?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            MessageVariant(
                language = v.language ?: "sv",
                header = header,
                details = details
            )
        }.orEmpty()
        if (variants.isEmpty()) return null
        return Deviation(
            deviationCaseId = deviationCaseId,
            version = versionInt,
            publishFrom = from,
            publishUpto = publish.upto?.let(::parseInstant),
            importanceLevel = priority?.importanceLevel,
            variants = variants,
            affectedLineIds = scope?.lines?.mapNotNull { it.id }.orEmpty(),
            affectedLineDesignations = scope?.lines
                ?.mapNotNull { it.designation?.takeIf { d -> d.isNotBlank() } }
                .orEmpty(),
            affectedSiteIds = scope?.stopAreas?.mapNotNull { it.id }.orEmpty()
        )
    }

    private fun parseInstant(s: String): Instant? = try {
        OffsetDateTime.parse(s).toInstant()
    } catch (e: DateTimeParseException) {
        Log.w(TAG, "Could not parse timestamp '$s'", e)
        null
    }

    private data class DeviationDto(
        @SerializedName("version") val version: Int? = null,
        @SerializedName("deviation_case_id") val deviationCaseId: Long? = null,
        @SerializedName("publish") val publish: PublishDto? = null,
        @SerializedName("priority") val priority: PriorityDto? = null,
        @SerializedName("message_variants") val messageVariants: List<MessageVariantDto>? = null,
        @SerializedName("scope") val scope: ScopeDto? = null
    )

    private data class PublishDto(
        @SerializedName("from") val from: String? = null,
        @SerializedName("upto") val upto: String? = null
    )

    private data class PriorityDto(
        @SerializedName("importance_level") val importanceLevel: Int? = null
    )

    private data class MessageVariantDto(
        @SerializedName("language") val language: String? = null,
        @SerializedName("header") val header: String? = null,
        @SerializedName("details") val details: String? = null
    )

    private data class ScopeDto(
        @SerializedName("lines") val lines: List<LineDto>? = null,
        @SerializedName("stop_areas") val stopAreas: List<StopAreaDto>? = null
    )

    private data class LineDto(
        @SerializedName("id") val id: Int? = null,
        @SerializedName("designation") val designation: String? = null
    )

    private data class StopAreaDto(
        @SerializedName("id") val id: Int? = null
    )

    companion object {
        private const val TAG = "Deviations"
        private const val ENDPOINT = "https://deviations.integration.sl.se/v1/messages"

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}
