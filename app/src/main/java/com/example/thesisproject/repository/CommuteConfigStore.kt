package com.example.thesisproject.repository

import android.content.Context
import com.example.thesisproject.model.CommuteConfig
import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonPrimitive
import com.google.gson.JsonSerializationContext
import com.google.gson.JsonSerializer
import com.google.gson.reflect.TypeToken
import java.lang.reflect.Type
import java.time.LocalTime
import java.time.format.DateTimeFormatter

class CommuteConfigStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val gson = GsonBuilder()
        .registerTypeAdapter(LocalTime::class.java, LocalTimeAdapter)
        .create()

    private val listType: Type = object : TypeToken<List<CommuteConfig>>() {}.type

    fun getAll(): List<CommuteConfig> {
        val json = prefs.getString(KEY_CONFIGS, null) ?: return emptyList()
        return try {
            gson.fromJson(json, listType) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Returns true if [candidate]'s time window overlaps any existing commute. */
    fun overlapsAny(candidate: CommuteConfig): Boolean {
        return getAll().any { existing -> overlaps(candidate, existing) }
    }

    /** Saves [config]. Returns false if it overlaps an existing window or is invalid. */
    fun add(config: CommuteConfig): Boolean {
        if (!config.timeWindowStart.isBefore(config.timeWindowEnd)) return false
        if (overlapsAny(config)) return false
        val updated = getAll() + config
        prefs.edit().putString(KEY_CONFIGS, gson.toJson(updated, listType)).apply()
        return true
    }

    private fun overlaps(a: CommuteConfig, b: CommuteConfig): Boolean {
        return a.timeWindowStart.isBefore(b.timeWindowEnd) &&
            b.timeWindowStart.isBefore(a.timeWindowEnd)
    }

    companion object {
        private const val PREFS_NAME = "commute_configs"
        private const val KEY_CONFIGS = "configs_json"
    }
}

private object LocalTimeAdapter : JsonSerializer<LocalTime>, JsonDeserializer<LocalTime> {
    private val formatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    override fun serialize(
        src: LocalTime,
        type: Type,
        ctx: JsonSerializationContext
    ): JsonElement = JsonPrimitive(src.format(formatter))

    override fun deserialize(
        json: JsonElement,
        type: Type,
        ctx: JsonDeserializationContext
    ): LocalTime = LocalTime.parse(json.asString, formatter)
}
