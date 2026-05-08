package com.example.thesisproject.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.thesisproject.BuildConfig
import com.example.thesisproject.MainActivity
import com.example.thesisproject.R
import com.example.thesisproject.repository.CommuteConfigStore
import com.example.thesisproject.repository.GtfsRealtimeRepository
import com.example.thesisproject.repository.SlDeviationsRepository
import com.example.thesisproject.repository.SlLineRepository
import com.example.thesisproject.repository.StopRepository
import com.example.thesisproject.tracking.LivePositionTracker
import com.example.thesisproject.tracking.TrackingState
import com.example.thesisproject.widget.WidgetStateDeriver
import com.example.thesisproject.widget.WidgetStateHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Step 8a sub-step 2: foreground service that runs the live commute tracker
 * outside the activity lifecycle. Lets the widget keep updating when the user
 * has the app backgrounded — the whole point of having a widget.
 *
 * Lifecycle:
 *  - Started by [MainActivity] (or, eventually, by the widget-add flow in
 *    Step 8b) when there's an active commute window.
 *  - Runs its own [LivePositionTracker] instance — independent of whatever
 *    tracker MainActivity may also be running (parallel polling during
 *    foreground; well under Bronze quota at 50 calls/min).
 *  - Pushes the latest [com.example.thesisproject.widget.WidgetCommuteState]
 *    into [WidgetStateHolder] on each tick. Step 8b will read from there.
 *  - Self-stops when the tracker reports [TrackingState.NoActiveCommute] —
 *    the active window has closed.
 *
 * Persistent notification is Android-mandatory for foreground services. We
 * use [NotificationManager.IMPORTANCE_MIN] + [NotificationCompat.PRIORITY_MIN]
 * so the icon stays out of the user's way (no sound, no heads-up, low
 * status-bar priority).
 */
class CommuteTrackingService : Service() {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var collectJob: Job? = null

    private lateinit var configStore: CommuteConfigStore
    private lateinit var lineRepository: SlLineRepository
    private lateinit var realtimeRepository: GtfsRealtimeRepository
    private lateinit var deviationsRepository: SlDeviationsRepository
    private lateinit var stopRepository: StopRepository
    private lateinit var tracker: LivePositionTracker

    override fun onCreate() {
        super.onCreate()
        configStore = CommuteConfigStore(applicationContext)
        lineRepository = SlLineRepository(applicationContext)
        realtimeRepository = GtfsRealtimeRepository()
        deviationsRepository = SlDeviationsRepository()
        stopRepository = StopRepository()
        tracker = LivePositionTracker(
            configStore = configStore,
            lineRepository = lineRepository,
            realtimeRepository = realtimeRepository,
            deviationsRepository = deviationsRepository,
            stopRepository = stopRepository,
            apiKey = BuildConfig.GTFS_REALTIME_KEY
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        if (collectJob?.isActive != true) {
            tracker.start(scope)
            collectJob = scope.launch {
                tracker.state.collect { state -> handleState(state) }
            }
            Log.d(TAG, "service started; tracker collecting")
        }
        // START_NOT_STICKY: if the OS kills the service due to memory pressure,
        // don't auto-restart. The next active commute window will start it again
        // via MainActivity / the widget-add flow. Auto-restart with stale state
        // would be worse than a brief gap.
        return START_NOT_STICKY
    }

    private fun handleState(state: TrackingState) {
        when (state) {
            is TrackingState.Polling -> {
                val widgetState = WidgetStateDeriver.derive(state, state.matchedDirection)
                WidgetStateHolder.update(widgetState)
                Log.d(
                    TAG,
                    "polling tick: phase=${widgetState?.phase} eta=${widgetState?.etaMinutes}min " +
                        "delta=${widgetState?.deltaMinutes}min vehicles=${state.vehicles.size}"
                )
            }
            is TrackingState.NoActiveCommute -> {
                // Active window has closed — there's nothing for the service to
                // do until another window opens. Stop self; user reopening the
                // app or adding a widget will start us again.
                Log.d(TAG, "no active commute; stopping service")
                WidgetStateHolder.update(WidgetStateDeriver.derive(state, null))
                stopSelf()
            }
            is TrackingState.Error -> {
                Log.w(TAG, "tracker error: ${state.message}")
                // Keep service running — transient failures recover on the next
                // poll cycle. Widget state is left as-is until next success.
            }
            TrackingState.Idle -> {
                // Pre-start phase only; nothing to push.
            }
        }
    }

    override fun onDestroy() {
        Log.d(TAG, "service destroyed; tearing down tracker")
        collectJob?.cancel()
        collectJob = null
        tracker.stop()
        scope.cancel()
        WidgetStateHolder.clear()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun ensureNotificationChannel() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.commute_tracking_channel_name),
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            description = getString(R.string.commute_tracking_channel_description)
            setShowBadge(false)
            enableLights(false)
            enableVibration(false)
        }
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingFlags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val pi = PendingIntent.getActivity(this, 0, openIntent, pendingFlags)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.commute_tracking_notification_title))
            .setContentText(getString(R.string.commute_tracking_notification_text))
            .setSmallIcon(R.drawable.ic_commute_notification)
            .setOngoing(true)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    companion object {
        private const val TAG = "CommuteService"
        const val CHANNEL_ID = "commute_tracking"
        private const val NOTIFICATION_ID = 1001

        /**
         * Starts the service if it isn't already running. On Android 8+ uses
         * [Context.startForegroundService] which mandates a [startForeground]
         * call within ~5 seconds — handled in [onStartCommand].
         */
        fun start(context: Context) {
            val intent = Intent(context, CommuteTrackingService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, CommuteTrackingService::class.java)
            context.stopService(intent)
        }
    }
}
