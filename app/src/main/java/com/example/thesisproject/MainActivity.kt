package com.example.thesisproject

import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.OvalShape
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
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
import com.example.thesisproject.model.Stop
import com.example.thesisproject.repository.CommuteConfigStore
import com.example.thesisproject.repository.SlLineRepository
import com.example.thesisproject.ui.MapViewModel
import com.example.thesisproject.ui.StopAdapter
import com.example.thesisproject.ui.StopConfigBottomSheet
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Polyline

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
        map.controller.setZoom(13.0)
        map.controller.setCenter(GeoPoint(STOCKHOLM_LAT, STOCKHOLM_LON))

        map.addMapListener(object : MapListener {
            override fun onScroll(event: ScrollEvent?): Boolean {
                updateCenterFromMap(); scheduleRebuild(); return false
            }
            override fun onZoom(event: ZoomEvent?): Boolean {
                updateCenterFromMap(); scheduleRebuild(); return false
            }
        })

        setupSearch()

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
        supportFragmentManager.setFragmentResultListener(
            StopConfigBottomSheet.RESULT_KEY_COMMUTE_SAVED,
            this
        ) { _, _ -> rebuildCommuteOverlays() }

        // Initial draw — if there are no saved commutes this is a no-op.
        rebuildCommuteOverlays()
    }

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
     */
    private fun rebuildVisibleMarkers() {
        if (allStops.isEmpty()) return

        val nearest = allStops.sortedBy { stop ->
            val dLat = stop.lat - centerLat
            val dLon = stop.lon - centerLon
            dLat * dLat + dLon * dLon
        }.take(MAX_MARKERS)

        visibleMarkers.forEach { map.overlays.remove(it) }
        visibleMarkers.clear()

        nearest.forEach { stop ->
            val marker = Marker(map)
            marker.position = GeoPoint(stop.lat, stop.lon)
            marker.title = stop.name
            marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
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
                    val polyline = Polyline()
                    polyline.setPoints(direction.polyline.map { GeoPoint(it[0], it[1]) })
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

    private fun makeStopDot(color: Int): Drawable {
        val sizePx = (resources.displayMetrics.density * 12f).toInt()
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

    override fun onResume() {
        super.onResume()
        map.onResume()
    }

    override fun onPause() {
        super.onPause()
        map.onPause()
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
