package com.example.thesisproject.widget

import android.content.Context

/**
 * Tracks which [com.example.thesisproject.model.CommuteConfig] each widget
 * instance is bound to. Persists across reboots in SharedPreferences keyed
 * by the AppWidget framework's per-instance integer ID.
 *
 * Multiple widgets can be bound to the same commute (e.g. one on the home
 * screen + one on the lock screen on Android 16+). Multiple commutes can be
 * bound to different widgets (different time windows, different lines).
 */
class WidgetBindingStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Returns the saved commute identity for [widgetId], or null if unbound. */
    fun getIdentity(widgetId: Int): String? = prefs.getString(keyFor(widgetId), null)

    fun setIdentity(widgetId: Int, identity: String) {
        prefs.edit().putString(keyFor(widgetId), identity).apply()
    }

    fun clear(widgetId: Int) {
        prefs.edit().remove(keyFor(widgetId)).apply()
    }

    private fun keyFor(widgetId: Int): String = "$KEY_PREFIX$widgetId"

    companion object {
        private const val PREFS_NAME = "widget_bindings"
        private const val KEY_PREFIX = "widget_"
    }
}
