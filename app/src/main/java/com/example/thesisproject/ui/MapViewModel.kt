package com.example.thesisproject.ui

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.thesisproject.model.Stop
import com.example.thesisproject.repository.StopRepository
import kotlinx.coroutines.launch

class MapViewModel : ViewModel() {

    private val repository = StopRepository()

    private val _stops = MutableLiveData<List<Stop>>()
    val stops: LiveData<List<Stop>> = _stops

    private val _error = MutableLiveData<String?>()
    val error: LiveData<String?> = _error

    fun loadStops() {
        viewModelScope.launch {
            try {
                _stops.value = repository.getStops()
                _error.value = null
            } catch (e: Exception) {
                Log.e(TAG, "loadStops failed", e)
                _stops.value = emptyList()
                _error.value = "${e.javaClass.simpleName}: ${e.message ?: "no message"}"
            }
        }
    }

    companion object {
        private const val TAG = "MapViewModel"
    }
}
