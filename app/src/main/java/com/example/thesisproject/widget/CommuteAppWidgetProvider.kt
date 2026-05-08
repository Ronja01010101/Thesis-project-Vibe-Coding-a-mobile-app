package com.example.thesisproject.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.example.thesisproject.MainActivity
import com.example.thesisproject.R
import com.example.thesisproject.repository.CommuteConfigStore

/**
 * Step 8b: AppWidgetProvider for the commute widget. Receives lifecycle
 * callbacks from the AppWidget framework + custom updates pushed by the
 * foreground service.
 *
 * Update path:
 *  - System cold update (boot, system refresh): [onUpdate] iterates widget
 *    IDs, looks up each binding, renders Dormant if no live state matches.
 *  - Live update during active commute window: foreground service calls
 *    [updateAll] on each tracker tick, passing the latest [WidgetCommuteState]
 *    + the active commute's identity. Widgets bound to the active commute
 *    render the live state; others render Dormant.
 */
class CommuteAppWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        val snapshot = WidgetStateHolder.snapshot.value
        val bindings = WidgetBindingStore(context)
        val configStore = CommuteConfigStore(context)
        val configsByIdentity = configStore.getAll().associateBy { WidgetCommuteIdentity.from(it) }
        appWidgetIds.forEach { id ->
            val views = buildViewsFor(
                context = context,
                widgetId = id,
                snapshot = snapshot,
                bindings = bindings,
                configsByIdentity = configsByIdentity
            )
            appWidgetManager.updateAppWidget(id, views)
        }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        val bindings = WidgetBindingStore(context)
        appWidgetIds.forEach { bindings.clear(it) }
    }

    companion object {
        /**
         * Push an update to every existing widget. Called from the foreground
         * service after each tracker tick — bypasses the Activity-bound update
         * path so the widget refreshes during background tracking.
         */
        fun updateAll(context: Context) {
            val mgr = AppWidgetManager.getInstance(context)
            val component = ComponentName(context, CommuteAppWidgetProvider::class.java)
            val ids = mgr.getAppWidgetIds(component)
            if (ids.isEmpty()) return
            val snapshot = WidgetStateHolder.snapshot.value
            val bindings = WidgetBindingStore(context)
            val configsByIdentity = CommuteConfigStore(context).getAll()
                .associateBy { WidgetCommuteIdentity.from(it) }
            ids.forEach { id ->
                val views = buildViewsFor(context, id, snapshot, bindings, configsByIdentity)
                mgr.updateAppWidget(id, views)
            }
        }

        private fun buildViewsFor(
            context: Context,
            widgetId: Int,
            snapshot: WidgetStateHolder.Snapshot,
            bindings: WidgetBindingStore,
            configsByIdentity: Map<String, com.example.thesisproject.model.CommuteConfig>
        ): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_commute)
            val boundIdentity = bindings.getIdentity(widgetId)
            val boundConfig = boundIdentity?.let { configsByIdentity[it] }

            when {
                // Widget is unbound (e.g. user pre-Step-8b add) — show full Dormant.
                boundIdentity == null || boundConfig == null ->
                    WidgetRenderer.renderDormant(context, views, null, null)

                // Widget bound to the currently-tracked commute and we have live state.
                snapshot.activeIdentity == boundIdentity && snapshot.state != null ->
                    WidgetRenderer.render(context, views, snapshot.state)

                // Widget bound to a commute that isn't currently active — show
                // Dormant with the bound commute's identity baked into the
                // header (line badge + direction), so the user can tell the
                // widget is wired up but waiting for its window.
                else -> WidgetRenderer.renderDormant(
                    context = context,
                    views = views,
                    lineLabel = boundConfig.lineDesignation?.takeIf { it.isNotBlank() } ?: boundConfig.lineId,
                    directionLabel = boundConfig.direction
                )
            }

            // Tap-to-open: any tap on the widget root opens MainActivity.
            // Covers half of P1-FR11; Step 9 in the build order can be marked
            // covered-by-Step-8b once this lands runtime-confirmed.
            val openIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val pi = PendingIntent.getActivity(
                context,
                widgetId,
                openIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            views.setOnClickPendingIntent(R.id.widget_root, pi)
            return views
        }
    }
}
