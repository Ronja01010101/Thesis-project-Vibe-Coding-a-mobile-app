package com.example.thesisproject.tracking

import android.util.Log
import com.example.thesisproject.model.CommuteConfig
import com.example.thesisproject.model.Deviation
import com.example.thesisproject.repository.CommuteConfigStore
import com.example.thesisproject.repository.GtfsRealtimeRepository
import com.example.thesisproject.repository.SlDeviationsRepository
import com.example.thesisproject.repository.SlLineRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Polling loop that fetches live vehicle positions and active deviations for
 * the user's currently active commute. "Active" means the saved commute whose
 * time-of-day window contains [clock]'s output.
 *
 * Lifecycle is owned by the caller — typically MainActivity starts the
 * tracker in onResume and stops it in onPause. This satisfies NFR10's
 * foreground-only constraint for Step 5; Step 8's lockscreen widget will
 * use a foreground service instead.
 *
 * Step 7 adds deviations: every [DEVIATION_POLL_RATIO]th vehicle tick we
 * also hit SL Deviations API (max once-per-minute per their docs). Deviations
 * are cached between fetches so vehicle ticks always emit a state with the
 * latest known deviations. ETag-based conditional GET avoids re-downloading
 * the body when nothing has changed.
 */
class LivePositionTracker(
    private val configStore: CommuteConfigStore,
    private val lineRepository: SlLineRepository,
    private val realtimeRepository: GtfsRealtimeRepository,
    private val deviationsRepository: SlDeviationsRepository,
    private val apiKey: String,
    private val pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
    private val clock: () -> LocalTime = { LocalTime.now() },
    private val instantClock: () -> Instant = { Instant.now() },
    private val zone: ZoneId = ZoneId.systemDefault()
) {

    private val _state = MutableStateFlow<TrackingState>(TrackingState.Idle)
    val state: StateFlow<TrackingState> = _state.asStateFlow()

    private var pollingJob: Job? = null

    private var tickCount = 0L
    private var cachedDeviations: List<Deviation> = emptyList()
    private var cachedDeviationEtag: String? = null
    private var cachedDeviationKey: Pair<String, String>? = null

    fun start(scope: CoroutineScope) {
        if (pollingJob?.isActive == true) return
        pollingJob = scope.launch {
            while (isActive) {
                pollOnce()
                delay(pollIntervalMs)
            }
        }
    }

    fun stop() {
        pollingJob?.cancel()
        pollingJob = null
        cachedDeviations = emptyList()
        cachedDeviationEtag = null
        cachedDeviationKey = null
        tickCount = 0L
        _state.value = TrackingState.Idle
    }

    private suspend fun pollOnce() {
        val configs = configStore.getAll()
        val active = configs.firstOrNull { isInWindow(it, clock()) }
        if (active == null) {
            // No active commute — drop any cached deviations so the next active
            // window starts fresh.
            cachedDeviations = emptyList()
            cachedDeviationEtag = null
            cachedDeviationKey = null
            _state.value = TrackingState.NoActiveCommute(configs.size)
            return
        }
        val designation = active.lineDesignation?.takeIf { it.isNotBlank() }
        if (designation == null) {
            _state.value = TrackingState.Error("Active commute has no line designation (legacy save — re-create it).")
            return
        }
        val matched = try {
            lineRepository.getMatchedLines(setOf(designation))
        } catch (e: Exception) {
            _state.value = TrackingState.Error("Catalog load failed: ${e.message ?: e.javaClass.simpleName}")
            return
        }
        val pair = lineRepository.matchConfig(matched, active)
        if (pair == null) {
            _state.value = TrackingState.Error("Could not match commute (line $designation) to catalog.")
            return
        }
        val (line, direction) = pair
        Log.d(TAG, "matched designation=$designation routeId=${line.routeId} routeType=${line.routeType} -> direction_id=${direction.directionId} headsign='${direction.headsign}' (config: stopName='${active.stopName}', directionCode=${active.directionCode}, direction='${active.direction}'), tripIds=${direction.tripIds.size}")
        if (direction.tripIds.isEmpty()) {
            _state.value = TrackingState.Error("No trip_ids for line $designation toward ${direction.headsign}.")
            return
        }
        val vehicles = try {
            realtimeRepository.fetchVehiclePositions(
                apiKey = apiKey,
                tripIds = direction.tripIds.toHashSet(),
                lineDesignation = designation,
                direction = direction.headsign
            )
        } catch (e: Exception) {
            Log.w(TAG, "Realtime fetch failed", e)
            _state.value = TrackingState.Error("Realtime fetch failed: ${e.message ?: e.javaClass.simpleName}")
            return
        }
        Log.d(TAG, "fetched ${vehicles.size} vehicles matching ${direction.tripIds.size} tripIds for line=$designation dir=${direction.headsign}")

        // Reset deviation cache if active commute's line changed since last
        // deviation fetch. (Stop is intentionally not part of the key — see
        // SlDeviationsRepository's docs for why we don't filter by site.)
        // Otherwise, only re-fetch every DEVIATION_POLL_RATIO ticks.
        val key = active.lineId to ""
        val keyChanged = key != cachedDeviationKey
        if (keyChanged) {
            cachedDeviationKey = key
            cachedDeviationEtag = null
            cachedDeviations = emptyList()
        }
        val shouldFetchDeviations = keyChanged || tickCount % DEVIATION_POLL_RATIO == 0L
        if (shouldFetchDeviations) {
            fetchDeviations(active)
        }
        tickCount++

        _state.value = TrackingState.Polling(
            activeCommute = active,
            vehicles = vehicles,
            lastUpdateMs = System.currentTimeMillis(),
            deviations = cachedDeviations
        )
    }

    private suspend fun fetchDeviations(active: CommuteConfig) {
        val lineIdInt = active.lineId.toIntOrNull()
        if (lineIdInt == null) {
            // CommuteConfig.lineId isn't integer-parseable. Could happen for
            // very old saves predating SL Transport integration.
            Log.w(TAG, "Skipping deviations: lineId='${active.lineId}' not integer-parseable")
            return
        }
        try {
            val result = deviationsRepository.fetchDeviations(
                lineId = lineIdInt,
                etag = cachedDeviationEtag,
                includeFuture = true
            )
            when (result) {
                SlDeviationsRepository.FetchResult.NotModified -> {
                    Log.d(TAG, "deviations unchanged (304); keeping ${cachedDeviations.size} cached")
                }
                is SlDeviationsRepository.FetchResult.Modified -> {
                    cachedDeviations = filterByCommuteWindow(result.deviations, active)
                    cachedDeviationEtag = result.etag
                    Log.d(TAG, "deviations refreshed: ${cachedDeviations.size} after window filter (raw=${result.deviations.size})")
                }
            }
        } catch (e: Exception) {
            // Don't tear down vehicle tracking on a deviations-API hiccup —
            // they're independent. Keep the previously-cached list and try
            // again next cycle.
            Log.w(TAG, "Deviations fetch failed (keeping cache)", e)
        }
    }

    /**
     * Keep only deviations that are active-now-or-during the active commute window.
     * Drops items whose `publishUpto` is already in the past, and items whose
     * `publishFrom` is after the commute window's end (e.g. planned work in 3 weeks).
     */
    private fun filterByCommuteWindow(devs: List<Deviation>, active: CommuteConfig): List<Deviation> {
        val now = instantClock()
        val commuteEnd = nextEndInstant(active, now)
        return devs.filter { dev ->
            val notExpired = dev.publishUpto == null || !dev.publishUpto.isBefore(now)
            val startsBeforeWindowEnds = !dev.publishFrom.isAfter(commuteEnd)
            notExpired && startsBeforeWindowEnds
        }
    }

    /**
     * Compute the next occurrence of the commute's `timeWindowEnd` after [now].
     * Handles cross-midnight windows (22:00-02:00) by rolling to tomorrow when
     * today's end-time has already passed.
     */
    private fun nextEndInstant(active: CommuteConfig, now: Instant): Instant {
        val zonedNow = ZonedDateTime.ofInstant(now, zone)
        val endToday = zonedNow.toLocalDate().atTime(active.timeWindowEnd).atZone(zone)
        return if (endToday.toInstant().isAfter(now)) {
            endToday.toInstant()
        } else {
            endToday.plusDays(1).toInstant()
        }
    }

    private fun isInWindow(config: CommuteConfig, now: LocalTime): Boolean {
        val start = config.timeWindowStart
        val end = config.timeWindowEnd
        return if (!start.isAfter(end)) {
            !now.isBefore(start) && !now.isAfter(end)
        } else {
            // Crosses midnight (e.g. 22:00–02:00).
            !now.isBefore(start) || !now.isAfter(end)
        }
    }

    companion object {
        private const val TAG = "LiveTracking"
        // 20s baseline polling per NFR10. With Bronze quota of 30k/month, this
        // covers ~165 minutes of active polling per day comfortably.
        const val DEFAULT_POLL_INTERVAL_MS = 20_000L
        // Deviations are slower-changing than vehicle positions; the docs cap
        // polling at "once a minute". 3 vehicle ticks = 60s, exactly aligned.
        private const val DEVIATION_POLL_RATIO = 3L
    }
}
