package com.example.thesisproject.ui

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.thesisproject.model.StopLineOption
import com.example.thesisproject.repository.StopRepository
import kotlinx.coroutines.launch

class StopConfigViewModel : ViewModel() {

    private val repository = StopRepository()

    private val _lineOptions = MutableLiveData<List<StopLineOption>>()
    val lineOptions: LiveData<List<StopLineOption>> = _lineOptions

    private val _loading = MutableLiveData(false)
    val loading: LiveData<Boolean> = _loading

    fun loadLineOptions(stopId: String) {
        _loading.value = true
        viewModelScope.launch {
            _lineOptions.value = repository.getLineOptionsForStop(stopId)
            _loading.value = false
        }
    }
}
