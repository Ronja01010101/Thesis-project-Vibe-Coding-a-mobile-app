package com.example.thesisproject.tracking

import com.example.thesisproject.model.CommuteConfig
import com.example.thesisproject.repository.CommuteConfigStore
import com.example.thesisproject.repository.GtfsRealtimeRepository
import com.example.thesisproject.repository.SlLineRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.LocalTime

/**
 * Polling loop that fetches live vehicle positions for the user's currently
 * active commute. "Active" means the saved commute whose time-of-day window
 * contains [clock]'s output.
 *
 * Lifecycle is owned by the caller — typically MainActivity starts the
 * tracker in onResume and stops it in onPause. This satisfies NFR10's
 * foreground-only constraint for Step 5; Step 8's lockscreen widget will
 * use a foreground service instead.
 */
class LivePositionTracker(
    private val configStore: CommuteConfigStore,
    private val lineRepository: SlLineRepository,
    private val realtimeRepository: GtfsRealtimeRepository,
    private val apiKey: String,
    private val pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
    private val clock: () -> LocalTime = { LocalTime.now() }
) {

    private val _state = MutableStateFlow<TrackingState>(TrackingState.Idle)
    val state: StateFlow<TrackingState> = _state.asStateFlow()

    private var pollingJob: Job? = null

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
        _state.value = TrackingState.Idle
    }

    private suspend fun pollOnce() {
        val configs = configStore.getAll()
        val active = configs.firstOrNull { isInWindow(it, clock()) }
        if (active == null) {
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
        val direction = pair.second
        if (direction.tripIds.isEmpty()) {
            _state.value = TrackingState.Error("No trip_ids for line $designation toward ${direction.headsign}.")
            return
        }
        try {
            val vehicles = realtimeRepository.fetchVehiclePositions(
                apiKey = apiKey,
                tripIds = direction.tripIds.toHashSet(),
                lineDesignation = designation,
                direction = direction.headsign
            )
            _state.value = TrackingState.Polling(
                activeCommute = active,
                vehicles = vehicles,
                lastUpdateMs = System.currentTimeMillis()
            )
        } catch (e: Exception) {
            _state.value = TrackingState.Error("Realtime fetch failed: ${e.message ?: e.javaClass.simpleName}")
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
        // 20s baseline polling per NFR10. With Bronze quota of 30k/month, this
        // covers ~165 minutes of active polling per day comfortably.
        const val DEFAULT_POLL_INTERVAL_MS = 20_000L
    }
}
