package com.example.thesisproject.widget

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-singleton holder for the latest [WidgetCommuteState]. Step 8a's
 * [com.example.thesisproject.service.CommuteTrackingService] writes the
 * derived state here on each poll tick; Step 8b's AppWidget surface will
 * read from here.
 *
 * Lives in the application process — survives Activity destruction but not
 * a process death. Acceptable: when the OS kills the process, the foreground
 * service is killed too, and the new process gets fresh state.
 *
 * Initial value is null so widgets rendered before any tracking has happened
 * can show their Dormant placeholder.
 */
object WidgetStateHolder {

    private val _state = MutableStateFlow<WidgetCommuteState?>(null)
    val state: StateFlow<WidgetCommuteState?> = _state.asStateFlow()

    fun update(s: WidgetCommuteState?) {
        _state.value = s
    }

    fun clear() {
        _state.value = null
    }
}
