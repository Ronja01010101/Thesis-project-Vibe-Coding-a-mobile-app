package com.example.thesisproject.repository

import android.content.Context
import com.example.thesisproject.model.CommuteConfig
import com.example.thesisproject.model.Line
import com.example.thesisproject.model.SlDirection
import com.example.thesisproject.model.SlLineEntry
import com.example.thesisproject.model.StopLineOption
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

    /** Built lazily by [getLineOptionsForStopName] on first call. Maps a
     *  normalised stop name → all (line, direction) options that serve it
     *  according to GTFS static. ~21k stops × ~1500 unique line-direction
     *  pairs deduplicated, ~1–2 MB total. */
    @Volatile
    private var cachedStopLineIndex: Map<String, List<StopLineOption>>? = null

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

        // For each entry in priority order, try direction matching strategies
        // in order of reliability:
        //   1. direction_id match (deterministic, when CommuteConfig has it)
        //   2. headsign match (works for most lines after BUG-005 fix)
        //   3. fallback to entry's first direction (last resort)
        ordered.forEach { entry ->
            val direction = matchDirection(entry, config)
            if (direction != null) return entry to direction
        }
        val fallbackEntry = ordered.firstOrNull { it.directions.isNotEmpty() } ?: return null
        return fallbackEntry to fallbackEntry.directions.first()
    }

    private fun matchDirection(entry: SlLineEntry, config: CommuteConfig): SlDirection? {
        if (entry.directions.isEmpty()) return null
        val configDirection = config.direction

        // 1) Stop-sequence-aware match. Trafiklab support explicitly says SL
        //    Transport `direction_code` (0/1/2) does NOT map to GTFS
        //    `direction_id` (0/1) and recommends matching via stop sequence
        //    instead. User picked stop X with direction label "Sofia" — find
        //    the GTFS direction where some stop named "Sofia" appears AFTER X
        //    in the stop ordering. That direction is unambiguously the one
        //    the user wants regardless of how SL or GTFS labels directions.
        //    Falls back through if stopName is missing (legacy save) or no
        //    direction has both stops in the right order.
        config.stopName?.takeIf { it.isNotBlank() }?.let { stopName ->
            entry.directions.forEach { dir ->
                val stopIndex = dir.stops.indexOfFirst { matchesByName(it.name, stopName) }
                if (stopIndex < 0) return@forEach
                val sofiaIsLater = dir.stops.asSequence()
                    .drop(stopIndex + 1)
                    .any { matchesByName(it.name, configDirection) }
                if (sofiaIsLater) return dir
            }
        }

        // 2) direction_code heuristic. SL uses 1-based (1/2 normal, 0 unknown);
        //    GTFS uses 0-based (0/1). Not documented as equivalent — Trafiklab
        //    explicitly says they don't share IDs — but for two-direction
        //    routes the `code - 1` mapping happens to work in practice. Keep
        //    as fallback for legacy saves without `stopName`.
        config.directionCode?.let { code ->
            entry.directions.firstOrNull { it.directionId == code }?.let { return it }
            if (code > 0) {
                entry.directions.firstOrNull { it.directionId == code - 1 }?.let { return it }
            }
        }

        // 3) Headsign matching — exact, then contains-either-way. Works for
        //    many lines after BUG-005's blank-headsign fallback populated
        //    direction.headsign with the trip's final-stop name.
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

    private fun matchesByName(a: String, b: String): Boolean {
        if (a.isBlank() || b.isBlank()) return false
        return a.equals(b, ignoreCase = true) ||
            a.contains(b, ignoreCase = true) ||
            b.contains(a, ignoreCase = true)
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

    /**
     * BUG-024 fix: returns all (line, direction) options that serve the stop
     * named [stopName], sourced from the bundled GTFS static catalog. Used by
     * the commute-config line picker to ensure every line that *ever* serves
     * the stop is selectable, regardless of whether buses are currently
     * running (which the live SL Transport Departures API requires).
     *
     * directionCode is set heuristically as `directionId + 1` (BUG-009 v2):
     * SL Transport's 1-based codes (1, 2 normal, 0 unknown) line up with
     * GTFS direction_id (0, 1) under that mapping for two-direction routes.
     * This is documented as a heuristic — the runtime tracker uses BUG-009
     * v3's stop-sequence-aware matching as the authoritative direction match,
     * so a wrong directionCode at picker time gets corrected later.
     *
     * Empty list when the stop name doesn't match anything in the catalog
     * (e.g. brand-new stop, or a name-format mismatch between SL Transport
     * site names and GTFS stop names — rare, but possible).
     */
    suspend fun getLineOptionsForStopName(stopName: String): List<StopLineOption> {
        if (stopName.isBlank()) return emptyList()
        val index = getStopLineIndex()
        val key = stopName.trim().lowercase()
        index[key]?.let { return it }
        // Fallback: contains-either-way scan over the keys. Slow (~21k key
        // comparisons) but rare — only when the SL Transport site name and
        // GTFS stop name have a format mismatch (e.g. trailing platform
        // suffix). Acceptable since this is config-time, not hot-path.
        return index.entries
            .firstOrNull { (catalogKey, _) ->
                catalogKey.contains(key) || key.contains(catalogKey)
            }
            ?.value
            .orEmpty()
    }

    /**
     * Builds and caches a map from normalised GTFS stop name → list of
     * [StopLineOption] (one per line × direction that serves the stop).
     * One full-catalog stream per app lifetime; subsequent calls O(1).
     *
     * Memory layout: a small pool of ~1500 unique [Line] objects (one per
     * line designation) and a small pool of ~3000 unique [StopLineOption]
     * objects (one per line × direction); the per-stop map values just hold
     * references into those pools. ~1–2 MB resident total.
     */
    private suspend fun getStopLineIndex(): Map<String, List<StopLineOption>> {
        cachedStopLineIndex?.let { return it }
        return withContext(Dispatchers.IO) {
            cachedStopLineIndex?.let { return@withContext it }
            val gson = Gson()
            val lineByDesignation = mutableMapOf<String, Line>()
            // BUG-025: pool by (designation, directionId, displayHeadsign) so
            // stops with different per-stop headsigns on the same direction
            // get distinct StopLineOption instances. Pool size grows from
            // ~1500 to a few thousand at most — still tiny.
            val optionByLineDirSign = mutableMapOf<Triple<String, Int, String>, StopLineOption>()
            val index = mutableMapOf<String, MutableList<StopLineOption>>()
            context.applicationContext.assets.open(ASSET_NAME).use { stream ->
                JsonReader(InputStreamReader(stream, StandardCharsets.UTF_8)).use { reader ->
                    reader.beginObject()
                    while (reader.hasNext()) {
                        when (reader.nextName()) {
                            "lines" -> {
                                reader.beginArray()
                                while (reader.hasNext()) {
                                    val entry: SlLineEntry =
                                        gson.fromJson(reader, SlLineEntry::class.java)
                                    val designation = entry.lineDesignation
                                    val transportMode = transportModeForRouteType(entry.routeType)
                                    val line = lineByDesignation.getOrPut(designation) {
                                        Line(
                                            id = designation.toIntOrNull()?.toString() ?: designation,
                                            name = designation,
                                            transportMode = transportMode
                                        )
                                    }
                                    entry.directions.forEach { dir ->
                                        dir.stops.forEach { stop ->
                                            if (stop.name.isBlank()) return@forEach
                                            // BUG-025: prefer the per-stop
                                            // destination sign (what SL
                                            // displays on the bus at THIS
                                            // stop) when it's set; fall back
                                            // to the trip-level headsign.
                                            val displayHeadsign = stop.stopHeadsign
                                                ?.takeIf { it.isNotBlank() }
                                                ?: dir.headsign
                                            val key = Triple(designation, dir.directionId, displayHeadsign)
                                            val opt = optionByLineDirSign.getOrPut(key) {
                                                StopLineOption(
                                                    line = line,
                                                    direction = displayHeadsign,
                                                    directionCode = dir.directionId + 1
                                                )
                                            }
                                            val nameKey = stop.name.trim().lowercase()
                                            index.getOrPut(nameKey) { mutableListOf() }.add(opt)
                                        }
                                    }
                                }
                                reader.endArray()
                            }
                            else -> reader.skipValue()
                        }
                    }
                    reader.endObject()
                }
            }
            // Deduplicate per-stop entries. Two options collapse only when
            // they share line + directionCode + direction string — so a stop
            // that sees both "Sofia" and "Tengdahlsgatan" on the same line+dir
            // (rare; would happen only if the same stop name appears at two
            // points in the same trip with different stop_headsigns) keeps
            // both rows.
            val frozen: Map<String, List<StopLineOption>> = index.mapValues { (_, list) ->
                list.distinctBy { Triple(it.line.id, it.directionCode, it.direction) }
            }
            cachedStopLineIndex = frozen
            frozen
        }
    }

    /**
     * Inverse of [matchesTransportMode] for indexing. Maps a GTFS route_type
     * back to the SL Transport API's transport mode string. Falls back to
     * "BUS" for unknown route types since buses are by far the most common.
     */
    private fun transportModeForRouteType(routeType: Int): String = when {
        routeType == 0 || routeType in 900..999 -> "TRAM"
        routeType == 1 || routeType in 400..699 -> "METRO"
        routeType == 2 || routeType in 100..399 -> "TRAIN"
        routeType == 4 || routeType in 1000..1099 || routeType in 1200..1299 -> "SHIP"
        routeType == 3 || routeType in 700..799 || routeType == 800 -> "BUS"
        else -> "BUS"
    }

    companion object {
        private const val ASSET_NAME = "sl-lines.json"
    }
}
