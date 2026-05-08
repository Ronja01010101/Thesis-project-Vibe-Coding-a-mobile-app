package com.example.thesisproject.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.thesisproject.model.StopLineOption
import com.example.thesisproject.repository.SlLineRepository
import com.example.thesisproject.repository.StopRepository
import kotlinx.coroutines.launch

/**
 * BUG-024: line picker now sources from both live SL Transport Departures AND
 * the bundled GTFS static catalog, merged with live taking precedence. Live
 * gives authoritative directionCodes for currently-running lines; static
 * fills in lines that don't have departures right now (e.g. day buses queried
 * at night, when the user wants to set up tomorrow's commute the night
 * before). Pre-fix, the picker was empty at night because Departures returns
 * 0 results for off-service lines.
 */
class StopConfigViewModel(application: Application) : AndroidViewModel(application) {

    private val stopRepository = StopRepository()
    private val lineRepository = SlLineRepository(application)

    private val _lineOptions = MutableLiveData<List<StopLineOption>>()
    val lineOptions: LiveData<List<StopLineOption>> = _lineOptions

    private val _loading = MutableLiveData(false)
    val loading: LiveData<Boolean> = _loading

    fun loadLineOptions(stopId: String, stopName: String) {
        _loading.value = true
        viewModelScope.launch {
            val live = stopRepository.getLineOptionsForStop(stopId)
            val static = lineRepository.getLineOptionsForStopName(stopName)
            _lineOptions.value = mergeLineOptions(live, static)
            _loading.value = false
        }
    }

    /**
     * Merge live + static line options. Live entries dominate (correct
     * directionCode straight from SL Transport API); static fills gaps
     * with the directionId+1 heuristic. Dedup key is (line.id, directionCode).
     */
    private fun mergeLineOptions(
        live: List<StopLineOption>,
        static: List<StopLineOption>
    ): List<StopLineOption> {
        val seen = mutableSetOf<Pair<String, Int>>()
        val result = mutableListOf<StopLineOption>()
        live.forEach { opt ->
            if (seen.add(opt.line.id to opt.directionCode)) result.add(opt)
        }
        static.forEach { opt ->
            // Static entries may collide with live on (line.id, heuristic
            // directionCode). When that happens, live is already in the list
            // — skip the static duplicate. When live and static disagree on
            // directionCode for the same line × direction (rare), both stay
            // and the user sees two entries; the saved commute will use
            // whichever they pick.
            if (seen.add(opt.line.id to opt.directionCode)) result.add(opt)
        }
        return result.sortedWith(
            compareBy({ it.line.transportMode }, { it.line.name }, { it.direction })
        )
    }
}
