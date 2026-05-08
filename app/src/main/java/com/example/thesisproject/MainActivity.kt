package com.example.thesisproject

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.OvalShape
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.thesisproject.model.CommuteConfig
import com.example.thesisproject.model.DataQuality
import com.example.thesisproject.model.Stop
import com.example.thesisproject.model.isInWindow
import com.example.thesisproject.service.CommuteTrackingService
import com.example.thesisproject.repository.CommuteConfigStore
import com.example.thesisproject.repository.GtfsRealtimeRepository
import com.example.thesisproject.repository.SlDeviationsRepository
import com.example.thesisproject.repository.SlLineRepository
import com.example.thesisproject.repository.StopRepository
import com.example.thesisproject.tracking.LivePositionTracker
import com.example.thesisproject.tracking.TrackingState
import com.example.thesisproject.ui.MapViewModel
import com.example.thesisproject.ui.StopAdapter
import com.example.thesisproject.ui.StopConfigBottomSheet
import com.example.thesisproject.widget.WidgetStateDeriver
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.infowindow.InfoWindow

class MainActivity : AppCompatActivity() {

    private lateinit var map: MapView
    private val viewModel: MapViewModel by viewModels()

    private lateinit var searchInput: EditText
    private lateinit var resultsCard: MaterialCardView
    private lateinit var resultsList: RecyclerView
    private lateinit var stopAdapter: StopAdapter

    private var allStops: List<Stop> = emptyList()
    private val visibleMarkers: MutableList<Marker> = mutableListOf()
    private val rebuildHandler = Handler(Looper.getMainLooper())
    private val rebuildRunnable = Runnable { rebuildVisibleMarkers() }

    private var centerLat = STOCKHOLM_LAT
    private var centerLon = STOCKHOLM_LON

    // Step 4b: saved-commute overlays drawn on top of regular stop markers.
    private val commuteStore by lazy { CommuteConfigStore(this) }
    private val lineRepository by lazy { SlLineRepository(this) }
    private val commuteOverlays: MutableList<Overlay> = mutableListOf()
    private var rebuildJob: Job? = null

    // Step 5: live-vehicle tracking. Lifecycle-bound — start in onResume,
    // stop in onPause. State observed by the verification overlay.
    private val realtimeRepository by lazy { GtfsRealtimeRepository() }
    private val deviationsRepository by lazy { SlDeviationsRepository() }
    // Step 8a: SL Departures repository, also feeds the widget state deriver.
    // Reused from Step 3's stop-config flow (different MapViewModel instance,
    // but the underlying Retrofit client is per-repository — harmless).
    private val stopRepository by lazy { StopRepository() }
    private val livePositionTracker by lazy {
        LivePositionTracker(
            configStore = commuteStore,
            lineRepository = lineRepository,
            realtimeRepository = realtimeRepository,
            deviationsRepository = deviationsRepository,
            stopRepository = stopRepository,
            apiKey = BuildConfig.GTFS_REALTIME_KEY
        )
    }
    private lateinit var liveStatusView: TextView
    private var lastTrackingState: TrackingState = TrackingState.Idle
    private var ageTickerJob: Job? = null

    // Step 7: deviations card (top warning bar). Hidden when no active
    // deviations apply to the current commute's line+stop. Tap toggles the
    // details expansion. See activity_main.xml `deviation_card`.
    private lateinit var deviationCard: MaterialCardView
    private lateinit var deviationHeader: TextView
    private lateinit var deviationCount: TextView
    private lateinit var deviationDetails: TextView
    private var deviationDetailsExpanded: Boolean = false

    // Step 8a: runtime grant for POST_NOTIFICATIONS (Android 13+). Without it,
    // foreground-service notifications are hidden even though the service runs.
    // Result is logged but not surfaced — service does its job either way.
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            Log.d("MainActivity", "POST_NOTIFICATIONS granted=$granted")
        }

    // Step 6: live vehicle markers + auto-fit-camera state.
    // vehicleMarkers are removed and re-added every state emission (small N — usually
    // single-digit count of vehicles per active commute, so the churn is negligible).
    // commutePolylines caches the GeoPoint list per commute index so the auto-fit
    // bounding box can include the route geometry. lastFittedCommuteKey suppresses
    // re-fitting on every poll; it resets when tracking leaves Polling.
    private val vehicleMarkers: MutableList<Marker> = mutableListOf()
    private val commutePolylines: MutableMap<Int, List<GeoPoint>> = mutableMapOf()
    private var lastFittedCommuteKey: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Configuration.getInstance().load(this, PreferenceManager.getDefaultSharedPreferences(this))
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        map = findViewById(R.id.map)
        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)
        // Disable OSMDroid's built-in zoom controller — it auto-hides and is
        // anchored at bottom-center where it overlaps the live_status banner.
        // We use the custom +/- buttons in the layout instead (zoom_in / zoom_out).
        map.zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
        map.controller.setZoom(13.0)
        map.controller.setCenter(GeoPoint(STOCKHOLM_LAT, STOCKHOLM_LON))

        // Tapping anywhere on the map (that isn't a marker) closes any open
        // InfoWindow. Added at index 0 so it gets the event after marker
        // overlays have had their chance to handle it.
        map.overlays.add(0, MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean {
                InfoWindow.closeAllInfoWindowsOn(map)
                return false
            }
            override fun longPressHelper(p: GeoPoint?): Boolean = false
        }))

        findViewById<Button>(R.id.zoom_in).setOnClickListener { map.controller.zoomIn() }
        findViewById<Button>(R.id.zoom_out).setOnClickListener { map.controller.zoomOut() }

        map.addMapListener(object : MapListener {
            override fun onScroll(event: ScrollEvent?): Boolean {
                updateCenterFromMap(); scheduleRebuild(); return false
            }
            override fun onZoom(event: ZoomEvent?): Boolean {
                updateCenterFromMap(); scheduleRebuild(); return false
            }
        })

        setupSearch()
        findViewById<Button>(R.id.manage_commutes_button).setOnClickListener {
            openManageCommutesDialog()
        }

        viewModel.stops.observe(this) { stops ->
            allStops = stops
            scheduleRebuild()
        }
        viewModel.error.observe(this) { msg ->
            if (!msg.isNullOrBlank() && !isFinishing) {
                AlertDialog.Builder(this)
                    .setTitle("Stops API error")
                    .setMessage(msg)
                    .setPositiveButton("OK", null)
                    .setCancelable(false)
                    .show()
            }
        }
        viewModel.loadStops()

        // Listen for the bottom sheet's "commute saved" broadcast so we can
        // redraw the line overlays as soon as a new commute is configured.
        // Step 8a runtime fix: also re-check whether to start the foreground
        // tracking service. Saving a commute whose window contains "now" while
        // the app is already running used to leave the service un-started
        // because the only check happened in onResume (which had run with
        // zero saved commutes at launch).
        supportFragmentManager.setFragmentResultListener(
            StopConfigBottomSheet.RESULT_KEY_COMMUTE_SAVED,
            this
        ) { _, _ ->
            rebuildCommuteOverlays()
            maybeStartTrackingService()
        }

        // Step 8a: ask for POST_NOTIFICATIONS permission once on Android 13+
        // so the foreground-service notification can render. Service runs
        // either way; this just controls whether the icon is visible.
        requestPostNotificationsIfNeeded()

        // Initial draw — if there are no saved commutes this is a no-op.
        rebuildCommuteOverlays()

        // Step 5: bind the verification overlay and start collecting tracker
        // state. Collection runs for the activity's lifetime (lifecycleScope);
        // the polling itself is gated by onResume/onPause so we don't burn
        // Trafiklab quota when the app isn't visible.
        liveStatusView = findViewById(R.id.live_status)

        // Step 7: bind the deviations card. Tap toggles details expansion;
        // visibility/text is driven by renderDeviations.
        deviationCard = findViewById(R.id.deviation_card)
        deviationHeader = findViewById(R.id.deviation_header)
        deviationCount = findViewById(R.id.deviation_count)
        deviationDetails = findViewById(R.id.deviation_details)
        deviationCard.setOnClickListener {
            deviationDetailsExpanded = !deviationDetailsExpanded
            deviationDetails.visibility = if (deviationDetailsExpanded) View.VISIBLE else View.GONE
        }

        lifecycleScope.launch {
            livePositionTracker.state.collect { state -> renderTrackingState(state) }
        }
    }

    private fun renderTrackingState(state: TrackingState) {
        lastTrackingState = state
        // Idle is the pre-onResume state; nothing useful to show, keep the bar
        // hidden so it doesn't visually compete with the map. All other states
        // surface user-visible information so we show the bar.
        liveStatusView.visibility = if (state is TrackingState.Idle) View.GONE else View.VISIBLE
        liveStatusView.text = when (state) {
            is TrackingState.Idle ->
                ""
            is TrackingState.NoActiveCommute -> if (state.savedCount == 0) {
                "No commutes saved — tap a stop on the map to add one."
            } else {
                "No commute active right now (${state.savedCount} saved)."
            }
            is TrackingState.Polling -> {
                val ageS = ((System.currentTimeMillis() - state.lastUpdateMs) / 1000)
                    .coerceAtLeast(0)
                val cfg = state.activeCommute
                val line = cfg.lineDesignation?.takeIf { it.isNotBlank() } ?: cfg.lineId
                val n = state.vehicles.size
                val noun = if (n == 1) "vehicle" else "vehicles"
                "Live: line $line → ${cfg.direction} — $n $noun, updated ${ageS}s ago"
            }
            is TrackingState.Error ->
                "Live data error: ${state.message}"
        }
        renderVehicles(state)
        renderDeviations(state)
        maybeAutoFit(state)
        logWidgetState(state)
    }

    /**
     * Step 8a sub-step 1: derive the widget render state from the current
     * tracker snapshot and log it. The widget surface itself ships in Step 8b;
     * for now this lets us verify the deriver works against real data in the
     * existing app (foreground service for off-app delivery is sub-step 2).
     */
    private fun logWidgetState(state: TrackingState) {
        if (state !is TrackingState.Polling) return
        val widgetState = WidgetStateDeriver.derive(state, state.matchedDirection) ?: return
        Log.d(
            "WidgetState",
            "phase=${widgetState.phase} eta=${widgetState.etaMinutes}min " +
                "delta=${widgetState.deltaMinutes}min busIndex=${widgetState.busIndex} " +
                "userStopIndex=${widgetState.userStopIndex}/${widgetState.stopCount} " +
                "line=${widgetState.lineDesignation} → ${widgetState.direction} " +
                "stop=${widgetState.stopName} " +
                (widgetState.deviation?.let { "deviation=\"${it.header.take(40)}\" (${it.totalCount} total)" } ?: "no-deviation")
        )
    }

    /**
     * Step 7: surface deviations matching the active commute. The card stays
     * hidden when there are none. When at least one applies, show the first
     * deviation's header (Swedish preferred per docs); if more than one, a
     * count badge is shown. Tapping the card toggles a details expansion that
     * concatenates all deviations' header+details text.
     */
    private fun renderDeviations(state: TrackingState) {
        val deviations = (state as? TrackingState.Polling)?.deviations.orEmpty()
        if (deviations.isEmpty()) {
            deviationCard.visibility = View.GONE
            deviationDetailsExpanded = false
            deviationDetails.visibility = View.GONE
            return
        }
        // Highest-importance first (per docs, importance_level is the only
        // priority field meant for sorting). Nulls last.
        val sorted = deviations.sortedByDescending { it.importanceLevel ?: Int.MIN_VALUE }
        val primary = sorted.first().preferredVariant("sv")
        deviationHeader.text = primary?.header.orEmpty()
        deviationCard.visibility = View.VISIBLE
        if (sorted.size > 1) {
            deviationCount.visibility = View.VISIBLE
            deviationCount.text = "+${sorted.size - 1}"
        } else {
            deviationCount.visibility = View.GONE
        }
        deviationDetails.text = sorted.joinToString(separator = "\n\n") { dev ->
            val v = dev.preferredVariant("sv")
            buildString {
                append(v?.header.orEmpty())
                val details = v?.details.orEmpty()
                if (details.isNotBlank()) {
                    append("\n")
                    append(details)
                }
            }
        }
        deviationDetails.visibility = if (deviationDetailsExpanded) View.VISIBLE else View.GONE
    }

    /**
     * Step 6: draw a marker for every vehicle in the current Polling state.
     * Markers use the active commute's palette colour. UNCERTAIN positions
     * (stale > 60 s) render desaturated with a grey outline. When a vehicle
     * reports a bearing we add a directional notch and rotate the icon.
     */
    private fun renderVehicles(state: TrackingState) {
        vehicleMarkers.forEach { map.overlays.remove(it) }
        vehicleMarkers.clear()
        if (state !is TrackingState.Polling) {
            map.invalidate()
            return
        }
        val configs = commuteStore.getAll()
        val activeIndex = configs.indexOf(state.activeCommute)
        val baseColor = if (activeIndex >= 0) {
            COMMUTE_PALETTE[activeIndex % COMMUTE_PALETTE.size]
        } else {
            COMMUTE_PALETTE[0]
        }
        // Step 7: every vehicle on the active commute's line gets the (!)
        // badge if any deviation matches that line. The Deviations API is
        // already filtered to the active commute's line+stop at request
        // time, so any non-empty deviations list applies to all of these
        // vehicles. (The API doesn't model per-trip disruption, only per-
        // line / per-stop — this is the most precise signal we can give.)
        val hasDeviation = state.deviations.isNotEmpty()
        state.vehicles.forEach { vehicle ->
            val isUncertain = vehicle.quality != DataQuality.LIVE
            val hasBearing = vehicle.bearing != null
            val marker = Marker(map)
            marker.position = GeoPoint(vehicle.lat, vehicle.lon)
            marker.icon = makeVehicleIcon(baseColor, isUncertain, hasBearing, hasDeviation)
            marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            // OSMDroid's Marker.rotation is counterclockwise. GTFS-RT bearing is
            // clockwise from north, so we negate to make the icon's notch point
            // in the actual direction of travel.
            vehicle.bearing?.let { marker.rotation = -it }
            marker.title = buildString {
                append(vehicle.lineId)
                append(" → ")
                append(vehicle.direction)
                if (isUncertain) append(" — uncertain")
                if (hasDeviation) append(" — disruption")
                vehicle.bearing?.let { append(" — bearing ${it.toInt()}°") }
            }
            vehicleMarkers.add(marker)
            map.overlays.add(marker)
        }
        map.invalidate()
    }

    private fun makeVehicleIcon(
        color: Int,
        isUncertain: Boolean,
        hasBearing: Boolean,
        hasDeviation: Boolean = false
    ): Drawable {
        val dpFactor = resources.displayMetrics.density
        val sizePx = (dpFactor * 26f).toInt()
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val center = sizePx / 2f
        val radius = (sizePx / 2f) - (dpFactor * 2f)

        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            alpha = if (isUncertain) 110 else 255
            style = Paint.Style.FILL
        }
        canvas.drawCircle(center, center, radius, fillPaint)

        val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = if (isUncertain) Color.GRAY else Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = dpFactor * if (isUncertain) 1.5f else 2f
        }
        canvas.drawCircle(center, center, radius, outlinePaint)

        if (hasBearing) {
            // Triangular notch fully inside the circle, near the top, pointing up
            // (= north, before rotation). Rotation is applied by OSMDroid via
            // marker.rotation so the notch ends up pointing in the direction of travel.
            val notchPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = if (isUncertain) Color.LTGRAY else Color.WHITE
                style = Paint.Style.FILL
            }
            val notchHalfWidth = radius * 0.45f
            val tipY = center - radius * 0.6f
            val baseY = center + radius * 0.05f
            val path = Path().apply {
                moveTo(center, tipY)
                lineTo(center - notchHalfWidth, baseY)
                lineTo(center + notchHalfWidth, baseY)
                close()
            }
            canvas.drawPath(path, notchPaint)
        }

        if (hasDeviation) {
            // Step 7: small (!) badge inside the icon, bottom-right area so it
            // doesn't conflict with the bearing notch (top-center). The whole
            // bitmap rotates with marker.rotation so the badge moves with the
            // vehicle's heading — acceptable; the badge meaning ("disruption
            // affects this line") doesn't depend on its position.
            val badgeCx = sizePx * 0.72f
            val badgeCy = sizePx * 0.72f
            val badgeR = sizePx * 0.22f
            val badgeFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = 0xFFE64A19.toInt() // deep orange, matches warning bar accent
                style = Paint.Style.FILL
            }
            canvas.drawCircle(badgeCx, badgeCy, badgeR, badgeFill)
            val badgeStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = Color.WHITE
                style = Paint.Style.STROKE
                strokeWidth = dpFactor * 1.0f
            }
            canvas.drawCircle(badgeCx, badgeCy, badgeR, badgeStroke)
            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = Color.WHITE
                textSize = dpFactor * 9f
                textAlign = Paint.Align.CENTER
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
            val textY = badgeCy - (textPaint.descent() + textPaint.ascent()) / 2f
            canvas.drawText("!", badgeCx, textY, textPaint)
        }

        return BitmapDrawable(resources, bitmap)
    }

    /**
     * Pan/zoom the map to include the active commute's polyline + currently-known
     * vehicle positions. Fires once per active commute session; if the polyline
     * isn't in the cache yet (rebuildCommuteOverlays is async and may not have
     * finished), we defer the fit to the next state emission instead of zooming
     * to vehicles only.
     */
    private fun maybeAutoFit(state: TrackingState) {
        if (state !is TrackingState.Polling) {
            lastFittedCommuteKey = null
            return
        }
        val key = state.activeCommute.fitKey()
        if (key == lastFittedCommuteKey) return

        val configs = commuteStore.getAll()
        val activeIndex = configs.indexOf(state.activeCommute)
        val polylinePoints = commutePolylines[activeIndex]
        if (polylinePoints.isNullOrEmpty()) return

        val allPoints = ArrayList<GeoPoint>(polylinePoints.size + state.vehicles.size).apply {
            addAll(polylinePoints)
            state.vehicles.forEach { add(GeoPoint(it.lat, it.lon)) }
        }
        val box = BoundingBox.fromGeoPoints(allPoints)
        // Padding in pixels. 80 leaves headroom for the search bar at top and
        // the live-status overlay at bottom without hiding the route's endpoints.
        map.zoomToBoundingBox(box, true, 80)
        lastFittedCommuteKey = key
    }

    private fun CommuteConfig.fitKey(): String =
        "$lineDesignation|$direction|$stopName|$timeWindowStart|$timeWindowEnd"

    private fun setupSearch() {
        searchInput = findViewById(R.id.search_input)
        resultsCard = findViewById(R.id.results_card)
        resultsList = findViewById(R.id.search_results)

        stopAdapter = StopAdapter { stop -> openCommuteConfig(stop) }
        resultsList.layoutManager = LinearLayoutManager(this)
        resultsList.adapter = stopAdapter

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                applyFilter(s?.toString().orEmpty())
            }
        })
    }

    private fun applyFilter(query: String) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            resultsCard.visibility = View.GONE
            stopAdapter.submit(emptyList())
            return
        }
        val matches = allStops
            .filter { it.name.contains(trimmed, ignoreCase = true) }
            .take(20)
        stopAdapter.submit(matches)
        resultsCard.visibility = if (matches.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun updateCenterFromMap() {
        val c = map.mapCenter
        val lat = c.latitude
        val lon = c.longitude
        // Guard against weird/uninitialised values; keep last good center otherwise.
        if (lat in -90.0..90.0 && lon in -180.0..180.0 && (lat != 0.0 || lon != 0.0)) {
            centerLat = lat
            centerLon = lon
        }
    }

    private fun scheduleRebuild() {
        rebuildHandler.removeCallbacks(rebuildRunnable)
        rebuildHandler.postDelayed(rebuildRunnable, REBUILD_DEBOUNCE_MS)
    }

    /**
     * Render the [MAX_MARKERS] stops closest to the current map center. We use
     * "nearest to center" rather than "inside boundingBox" because boundingBox
     * is unreliable until the MapView has been measured, and on slow emulators
     * the stops can arrive first. Distance to center is always well-defined.
     *
     * Stops are hidden when the map is zoomed out below [MIN_STOP_ZOOM] so the
     * initial fully-zoomed-out map isn't a wall of dots — the user has to zoom
     * in once before stops appear.
     */
    private fun rebuildVisibleMarkers() {
        visibleMarkers.forEach { map.overlays.remove(it) }
        visibleMarkers.clear()

        if (allStops.isEmpty() || map.zoomLevelDouble < MIN_STOP_ZOOM) {
            map.invalidate()
            return
        }

        val nearest = allStops.sortedBy { stop ->
            val dLat = stop.lat - centerLat
            val dLon = stop.lon - centerLon
            dLat * dLat + dLon * dLon
        }.take(MAX_MARKERS)

        val stopIcon = makeStopDot(STOP_DOT_COLOR, STOP_DOT_DIAMETER_DP)
        nearest.forEach { stop ->
            val marker = Marker(map)
            marker.position = GeoPoint(stop.lat, stop.lon)
            marker.title = stop.name
            marker.icon = stopIcon
            marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            marker.setOnMarkerClickListener { _, _ ->
                openCommuteConfig(stop)
                true
            }
            visibleMarkers.add(marker)
            map.overlays.add(marker)
        }
        map.invalidate()
    }

    /**
     * Step 4b: For each saved commute config, find the matching line+direction
     * in the bundled GTFS data and draw its polyline + stop markers. Streams
     * just the matching line entries from sl-lines.json so we don't blow heap
     * with the full catalog. Called on activity start and whenever a new
     * commute is saved (via the FragmentResultListener).
     */
    private fun rebuildCommuteOverlays() {
        rebuildJob?.cancel()
        val configs = commuteStore.getAll()
        val designations = configs
            .mapNotNull { it.lineDesignation?.takeIf { d -> d.isNotBlank() } }
            .toSet()

        // Always clear first so the map state is consistent immediately.
        commuteOverlays.forEach { map.overlays.remove(it) }
        commuteOverlays.clear()
        commutePolylines.clear()
        if (designations.isEmpty()) {
            map.invalidate()
            return
        }

        rebuildJob = lifecycleScope.launch {
            val matched = try {
                lineRepository.getMatchedLines(designations)
            } catch (e: Exception) {
                emptyMap()
            }
            if (matched.isEmpty()) {
                map.invalidate()
                return@launch
            }

            configs.forEachIndexed { index, config ->
                val color = COMMUTE_PALETTE[index % COMMUTE_PALETTE.size]
                val pair = lineRepository.matchConfig(matched, config) ?: return@forEachIndexed
                val (_, direction) = pair

                if (direction.polyline.isNotEmpty()) {
                    val points = direction.polyline.map { GeoPoint(it[0], it[1]) }
                    commutePolylines[index] = points
                    val polyline = Polyline()
                    polyline.setPoints(points)
                    polyline.outlinePaint.color = color
                    polyline.outlinePaint.strokeWidth = 12f
                    polyline.outlinePaint.alpha = 180
                    map.overlays.add(polyline)
                    commuteOverlays.add(polyline)
                }

                val dotIcon = makeStopDot(color)
                direction.stops.forEach { stop ->
                    val marker = Marker(map)
                    marker.position = GeoPoint(stop.lat, stop.lon)
                    marker.title = "${config.lineDesignation ?: "?"}: ${stop.name}"
                    marker.icon = dotIcon
                    marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    map.overlays.add(marker)
                    commuteOverlays.add(marker)
                }
            }
            map.invalidate()
        }
    }

    private fun makeStopDot(color: Int, sizeDp: Float = 12f): Drawable {
        val sizePx = (resources.displayMetrics.density * sizeDp).toInt()
        val shape = ShapeDrawable(OvalShape())
        shape.intrinsicWidth = sizePx
        shape.intrinsicHeight = sizePx
        shape.paint.color = color
        shape.paint.style = Paint.Style.FILL
        return shape
    }

    private fun openCommuteConfig(stop: Stop) {
        searchInput.setText("")
        resultsCard.visibility = View.GONE
        StopConfigBottomSheet.newInstance(stop.id, stop.name)
            .show(supportFragmentManager, "commute_config")
    }

    /**
     * Lists saved commutes; tapping one removes it. Used during testing so
     * the user can iterate on commute configs without rebuilding the app.
     * Will be revisited / replaced by a proper "edit commutes" screen later.
     */
    private fun openManageCommutesDialog() {
        val configs = commuteStore.getAll()
        if (configs.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("Saved commutes")
                .setMessage("No commutes saved yet. Tap a stop on the map to add one.")
                .setPositiveButton("OK", null)
                .show()
            return
        }
        val labels = configs.map { c ->
            val line = c.lineDesignation?.takeIf { it.isNotBlank() } ?: c.lineId
            val mode = c.transportMode?.takeIf { it.isNotBlank() }?.let { "$it " } ?: ""
            "$mode$line → ${c.direction}\n" +
                "%02d:%02d–%02d:%02d".format(
                    c.timeWindowStart.hour, c.timeWindowStart.minute,
                    c.timeWindowEnd.hour, c.timeWindowEnd.minute
                )
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Saved commutes — tap to delete")
            .setItems(labels) { _, which ->
                if (commuteStore.removeAt(which)) {
                    Toast.makeText(this, "Commute removed", Toast.LENGTH_SHORT).show()
                    rebuildCommuteOverlays()
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        map.onResume()
        livePositionTracker.start(lifecycleScope)
        // Step 8a sub-step 2: spin up the foreground service when the user
        // currently has an active commute window. Service self-stops once
        // the window closes (one tracker tick later), so we don't have to
        // manage stop on a window-expiry timer here. If no window is active
        // right now, we don't start the service — saves the persistent
        // notification slot until it's actually relevant.
        maybeStartTrackingService()
        // 1-second ticker re-renders the "updated Xs ago" line so the user
        // can see the counter incrementing between polls. The tracker itself
        // only emits a new state every 20s, so without this the text would
        // look frozen even when polling is firing normally.
        ageTickerJob = lifecycleScope.launch {
            while (isActive) {
                delay(1_000L)
                if (lastTrackingState is TrackingState.Polling) {
                    renderTrackingState(lastTrackingState)
                }
            }
        }
    }

    /**
     * Whether any saved commute's daily window contains the current local time.
     * Used as a gate for starting the foreground service; the service itself
     * checks the same condition on every poll and self-stops when it flips false.
     */
    private fun anyCommuteCurrentlyActive(): Boolean {
        val now = java.time.LocalTime.now()
        return commuteStore.getAll().any { it.isInWindow(now) }
    }

    /**
     * Call sites: onResume (app coming to foreground), and the commute_saved
     * fragment-result handler (commute added/changed during a session). Both
     * paths need to be able to start the service; the gate only matters for
     * "is a window currently active" — `CommuteTrackingService.start` is
     * idempotent so re-calling it on an already-running service is a no-op
     * beyond a fresh `onStartCommand`.
     */
    private fun maybeStartTrackingService() {
        if (anyCommuteCurrentlyActive()) {
            CommuteTrackingService.start(this)
        }
    }

    private fun requestPostNotificationsIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onPause() {
        super.onPause()
        map.onPause()
        livePositionTracker.stop()
        ageTickerJob?.cancel()
        ageTickerJob = null
    }

    override fun onDestroy() {
        rebuildHandler.removeCallbacks(rebuildRunnable)
        super.onDestroy()
    }

    companion object {
        private const val STOCKHOLM_LAT = 59.3293
        private const val STOCKHOLM_LON = 18.0686
        private const val MAX_MARKERS = 400
        private const val REBUILD_DEBOUNCE_MS = 200L
        // Below this zoom level, plain stop markers are hidden — keeps the
        // initial city-wide view uncluttered. The default starting zoom is 13,
        // so the user has to zoom in once to see stops.
        private const val MIN_STOP_ZOOM = 14.0
        private const val STOP_DOT_DIAMETER_DP = 6f
        // Muted blue-grey so the dots are visible against OSM tiles but don't
        // dominate the map.
        private const val STOP_DOT_COLOR = 0xFF607D8B.toInt()

        // Color cycle for commute overlays — distinct + colour-blind friendly enough
        // that two or three saved commutes are easy to tell apart on a basic map tile.
        private val COMMUTE_PALETTE = intArrayOf(
            0xFF1976D2.toInt(),  // blue
            0xFFE53935.toInt(),  // red
            0xFF43A047.toInt(),  // green
            0xFFFB8C00.toInt(),  // orange
            0xFF8E24AA.toInt()   // purple
        )
    }
}
