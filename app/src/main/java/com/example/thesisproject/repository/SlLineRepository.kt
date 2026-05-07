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
 * Reads the bundled `sl-lines.json` asset (~15 MB) but **does not** load it
 * fully into memory. Streams through with Gson's [JsonReader] and keeps only
 * the line entries whose designations match a passed-in set — typically the
 * 1–3 lines the user has saved as commutes. This caps sustained heap use at
 * ~100 KB instead of the ~10–15 MB the full catalog object graph would use.
 *
 * If [getMatchedLines] is called with an empty set, no parse happens at all.
 */
class SlLineRepository(private val context: Context) {

    @Volatile
    private var cachedDesignations: Set<String>? = null

    @Volatile
    private var cachedMatched: Map<String, SlLineEntry>? = null

    suspend fun getMatchedLines(designations: Set<String>): Map<String, SlLineEntry> {
        if (designations.isEmpty()) return emptyMap()
        cachedMatched?.let { cached ->
            if (cachedDesignations == designations) return cached
        }
        return withContext(Dispatchers.IO) {
            val gson = Gson()
            val result = mutableMapOf<String, SlLineEntry>()
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
                                        result[entry.lineDesignation] = entry
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
            cachedMatched = result
            cachedDesignations = designations
            result
        }
    }

    /**
     * Finds the line + direction in [matched] that corresponds to [config].
     * Returns null if the config has no designation (legacy save) or its
     * designation isn't in the matched map.
     */
    fun matchConfig(
        matched: Map<String, SlLineEntry>,
        config: CommuteConfig
    ): Pair<SlLineEntry, SlDirection>? {
        val designation = config.lineDesignation?.takeIf { it.isNotBlank() } ?: return null
        val line = matched[designation] ?: return null
        val direction = matchDirection(line, config.direction) ?: return null
        return line to direction
    }

    private fun matchDirection(line: SlLineEntry, configDirection: String): SlDirection? {
        if (line.directions.isEmpty()) return null
        line.directions.firstOrNull { it.headsign.equals(configDirection, ignoreCase = true) }
            ?.let { return it }
        line.directions.firstOrNull {
            it.headsign.isNotBlank() && it.headsign.contains(configDirection, ignoreCase = true)
        }?.let { return it }
        line.directions.firstOrNull {
            it.headsign.isNotBlank() && configDirection.contains(it.headsign, ignoreCase = true)
        }?.let { return it }
        return line.directions.first()
    }

    companion object {
        private const val ASSET_NAME = "sl-lines.json"
    }
}
