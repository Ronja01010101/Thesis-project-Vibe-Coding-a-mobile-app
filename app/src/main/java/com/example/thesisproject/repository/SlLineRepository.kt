package com.example.thesisproject.repository

import android.content.Context
import com.example.thesisproject.model.CommuteConfig
import com.example.thesisproject.model.SlDirection
import com.example.thesisproject.model.SlLineCatalog
import com.example.thesisproject.model.SlLineEntry
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads and caches the bundled `sl-lines.json` asset (produced by the
 * `extractGtfs` Gradle task). The JSON is large (~15 MB) so the first
 * call is slow — always invoke from a background coroutine.
 */
class SlLineRepository(private val context: Context) {

    @Volatile
    private var cachedCatalog: SlLineCatalog? = null

    suspend fun getCatalog(): SlLineCatalog = withContext(Dispatchers.IO) {
        cachedCatalog?.let { return@withContext it }
        val json = context.applicationContext.assets.open(ASSET_NAME)
            .bufferedReader().use { it.readText() }
        val parsed = Gson().fromJson(json, SlLineCatalog::class.java)
        cachedCatalog = parsed
        parsed
    }

    /**
     * Finds the line + direction in [catalog] that matches a saved [config].
     * Match strategy:
     *   1. Exact case-insensitive headsign match
     *   2. Headsign contains the config's direction string (or vice versa)
     *   3. Fall back to the line's first direction
     * Returns null if the config has no designation (legacy save) or the
     * designation isn't in the catalog.
     */
    fun matchConfig(catalog: SlLineCatalog, config: CommuteConfig): Pair<SlLineEntry, SlDirection>? {
        val designation = config.lineDesignation?.takeIf { it.isNotBlank() } ?: return null
        val line = catalog.lines.firstOrNull { it.lineDesignation == designation } ?: return null
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
