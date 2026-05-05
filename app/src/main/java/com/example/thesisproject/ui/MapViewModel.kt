package com.example.thesisproject.ui

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

    fun loadStops() {
        viewModelScope.launch {
            _stops.value = repository.getStops()
        }
    }
}
