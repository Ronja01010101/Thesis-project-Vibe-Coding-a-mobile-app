package com.example.thesisproject.repository

import android.content.Context
import com.example.thesisproject.model.CommuteConfig
import com.example.thesisproject.model.SlDirection
import com.example.thesisproject.model.SlLineEntry
import com.google.gson.Gson
import com.google.gson.stream.JsonReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

/**
 * Reads the bundled `sl-lines.json` asset (~18 MB) but **does not** load it
 * fully into memory. Streams through with Gson's [JsonReader] and keeps only
 * the line entries whose designations match a passed-in set — typically the
 * 1–3 lines the user has saved as commutes. This caps sustained heap use at
 * ~100 KB instead of the ~10–15 MB the full catalog object graph would use.
 *
 * Multiple routes can share the same designation (e.g. line "3" exists as
 * a bus and as a boat). [getMatchedLines] returns ALL matching entries per
 * designation; [matchConfig] then disambiguates using transport mode and
 * direction-headsign matching.
 *
 * If [getMatchedLines] is called with an empty set, no parse happens at all.
 */
class SlLineRepository(private val context: Context) {

    @Volatile
    private var cachedDesignations: Set<String>? = null

    @Volatile
    private var cachedMatched: Map<String, List<SlLineEntry>>? = null

    suspend fun getMatchedLines(designations: Set<String>): Map<String, List<SlLineEntry>> {
        if (designations.isEmpty()) return emptyMap()
        cachedMatched?.let { cached ->
            if (cachedDesignations == designations) return cached
        }
        return withContext(Dispatchers.IO) {
            val gson = Gson()
            val result = mutableMapOf<String, MutableList<SlLineEntry>>()
            context.applicationContext.assets.open(ASSET_NAME).use { stream ->
                JsonReader(InputStreamReader(stream, StandardCharsets.UTF_8)).use { reader ->
                    reader.beginObject()
                    while (reader.hasNext()) {
                        when (reader.nextName()) {
                            "lines" -> {
                                reader.beginArray()
                                while (reader.hasNext()) {
                                    val entry: SlLineEntry = gson.fromJson(reader, SlLineEntry::class.java)
                                    if (entry.lineDesignation in designations) {
                                        result.getOrPut(entry.lineDesignation) { mutableListOf() }.add(entry)
                                    }
                                    // Non-matching entries become unreachable here and will be GC'd
                                    // before the next iteration — keeping peak memory tiny.
                                }
                                reader.endArray()
                            }
                            else -> reader.skipValue()
                        }
                    }
                    reader.endObject()
                }
            }
            val frozen: Map<String, List<SlLineEntry>> = result.mapValues { it.value.toList() }
            cachedMatched = frozen
            cachedDesignations = designations
            frozen
        }
    }

    /**
     * Finds the line + direction in [matched] that corresponds to [config].
     * Picks the right route variant when multiple routes share a designation
     * (e.g. bus 3 vs boat 3): prefers entries whose `routeType` matches the
     * config's `transportMode`, then prefers entries that have a direction
     * with a matching headsign. Returns null if no entry / direction can be
     * matched at all.
     */
    fun matchConfig(
        matched: Map<String, List<SlLineEntry>>,
        config: CommuteConfig
    ): Pair<SlLineEntry, SlDirection>? {
        val designation = config.lineDesignation?.takeIf { it.isNotBlank() } ?: return null
        val entries = matched[designation]?.takeIf { it.isNotEmpty() } ?: return null

        // Order entries: transport-mode matches first (e.g. config says BUS,
        // route_type 3 / 700–799 first), other entries after. If no
        // transport mode is set on the config (legacy saves), keep order.
        val ordered = if (!config.transportMode.isNullOrBlank()) {
            val (matching, other) = entries.partition { matchesTransportMode(it.routeType, config.transportMode) }
            matching + other
        } else {
            entries
        }

        // For each entry in priority order, look for a direction matching
        // config.direction. Use the first entry that can produce a match.
        ordered.forEach { entry ->
            val direction = matchDirection(entry, config.direction)
            if (direction != null) return entry to direction
        }
        // No headsign match anywhere — fall back to first entry's first
        // direction so we at least show something rather than nothing.
        val fallbackEntry = ordered.firstOrNull { it.directions.isNotEmpty() } ?: return null
        return fallbackEntry to fallbackEntry.directions.first()
    }

    private fun matchDirection(entry: SlLineEntry, configDirection: String): SlDirection? {
        if (entry.directions.isEmpty()) return null
        entry.directions.firstOrNull { it.headsign.equals(configDirection, ignoreCase = true) }
            ?.let { return it }
        entry.directions.firstOrNull {
            it.headsign.isNotBlank() && it.headsign.contains(configDirection, ignoreCase = true)
        }?.let { return it }
        entry.directions.firstOrNull {
            it.headsign.isNotBlank() && configDirection.contains(it.headsign, ignoreCase = true)
        }?.let { return it }
        return null
    }

    /**
     * Maps SL Transport API transport-mode strings (BUS/METRO/TRAIN/TRAM/SHIP)
     * to GTFS route_type ranges. Covers both the original GTFS basic types
     * (0–7) and the extended Hierarchical Vehicle Type ranges that SL's feed
     * uses (e.g. 700–799 for buses, 1000–1099 for water transport).
     */
    private fun matchesTransportMode(routeType: Int, mode: String): Boolean {
        return when (mode.uppercase()) {
            "BUS" -> routeType == 3 || routeType in 700..799 || routeType == 800 // includes trolleybus
            "METRO" -> routeType == 1 || routeType in 400..699
            "TRAIN" -> routeType == 2 || routeType in 100..399
            "TRAM" -> routeType == 0 || routeType in 900..999
            "SHIP" -> routeType == 4 || routeType in 1000..1099 || routeType in 1200..1299
            else -> false
        }
    }

    companion object {
        private const val ASSET_NAME = "sl-lines.json"
    }
}
