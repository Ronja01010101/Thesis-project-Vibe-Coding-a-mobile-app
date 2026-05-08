package com.example.thesisproject.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.example.thesisproject.MainActivity
import com.example.thesisproject.R
import com.example.thesisproject.model.CommuteConfig
import com.example.thesisproject.model.isInWindow
import com.example.thesisproject.repository.CommuteConfigStore
import com.example.thesisproject.service.CommuteTrackingService
import java.time.LocalTime

/**
 * Step 8b: shown when the user drags the commute widget onto their home
 * screen. Lists saved [com.example.thesisproject.model.CommuteConfig]s;
 * the user picks one, we save the (widgetId → identity) binding, do an
 * initial render of the widget, and finish with RESULT_OK so the AppWidget
 * framework keeps the widget. Cancelling the dialog or backing out leaves
 * the widget unbound — the framework auto-removes it on RESULT_CANCELED.
 *
 * If the user has no commutes saved, the dialog shows an empty-state
 * message and a button that opens MainActivity. The widget add itself is
 * still cancelled in that case (user must re-add after configuring).
 */
class WidgetConfigActivity : AppCompatActivity() {

    private var widgetId: Int = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Default to cancelled until the user explicitly picks a commute —
        // back-press or dialog dismissal then results in widget auto-removal,
        // which is the right behaviour for "user changed their mind".
        setResult(Activity.RESULT_CANCELED)

        widgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        val configs = CommuteConfigStore(this).getAll()
        if (configs.isEmpty()) {
            showEmptyDialog()
        } else {
            showPickerDialog(configs)
        }
    }

    private fun showPickerDialog(configs: List<CommuteConfig>) {
        val labels = configs.map { c ->
            val line = c.lineDesignation?.takeIf { it.isNotBlank() } ?: c.lineId
            val mode = c.transportMode?.takeIf { it.isNotBlank() }?.let { "$it " } ?: ""
            val window = "%02d:%02d–%02d:%02d".format(
                c.timeWindowStart.hour, c.timeWindowStart.minute,
                c.timeWindowEnd.hour, c.timeWindowEnd.minute
            )
            "$mode$line → ${c.direction}\n$window · from ${c.stopName ?: c.stopId}"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle(R.string.widget_config_title)
            .setItems(labels) { _, which ->
                bindAndFinish(configs[which])
            }
            .setOnCancelListener { finish() }
            .setNegativeButton(android.R.string.cancel) { _, _ -> finish() }
            .show()
    }

    private fun showEmptyDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.widget_config_title)
            .setMessage(R.string.widget_config_empty)
            .setPositiveButton(R.string.app_name) { _, _ ->
                startActivity(Intent(this, MainActivity::class.java))
                finish()
            }
            .setOnCancelListener { finish() }
            .setNegativeButton(android.R.string.cancel) { _, _ -> finish() }
            .show()
    }

    private fun bindAndFinish(picked: CommuteConfig) {
        val identity = WidgetCommuteIdentity.from(picked)
        WidgetBindingStore(this).setIdentity(widgetId, identity)
        // Render the widget once now (Dormant placeholder using the picked
        // commute's labels) so the user sees something other than the
        // initialLayout's blank "—" the moment configuration finishes.
        CommuteAppWidgetProvider.updateAll(this)
        // Kick the foreground service in case the picked commute is currently
        // active — same idempotent gate as MainActivity's resume hook.
        if (picked.isInWindow(LocalTime.now())) {
            CommuteTrackingService.start(this)
        }
        val resultValue = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
        setResult(Activity.RESULT_OK, resultValue)
        finish()
    }
}
