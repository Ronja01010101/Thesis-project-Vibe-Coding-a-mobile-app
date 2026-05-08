package com.example.thesisproject.widget

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-singleton holder for the latest [WidgetCommuteState]. Step 8a's
 * [com.example.thesisproject.service.CommuteTrackingService] writes the
 * derived state here on each poll tick; Step 8b's AppWidget surface reads
 * via [snapshot] when each widget is updated.
 *
 * Lives in the application process — survives Activity destruction but not
 * a process death. Acceptable: when the OS kills the process, the foreground
 * service is killed too, and the new process gets fresh state.
 *
 * [activeIdentity] tracks which commute the held [state] is for, so widgets
 * bound to *other* commutes can render their own Dormant placeholder
 * instead of incorrectly showing this commute's data.
 */
object WidgetStateHolder {

    data class Snapshot(
        val state: WidgetCommuteState?,
        val activeIdentity: String?
    )

    private val _snapshot = MutableStateFlow(Snapshot(null, null))
    val snapshot: StateFlow<Snapshot> = _snapshot.asStateFlow()

    fun update(state: WidgetCommuteState?, activeIdentity: String?) {
        _snapshot.value = Snapshot(state, activeIdentity)
    }

    fun clear() {
        _snapshot.value = Snapshot(null, null)
    }
}
