package com.example.thesisproject

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.thesisproject.model.Stop
import com.example.thesisproject.ui.MapViewModel
import com.example.thesisproject.ui.StopAdapter
import com.example.thesisproject.ui.StopConfigBottomSheet
import com.google.android.material.card.MaterialCardView
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker

class MainActivity : AppCompatActivity() {

    private lateinit var map: MapView
    private val viewModel: MapViewModel by viewModels()

    private lateinit var searchInput: EditText
    private lateinit var resultsCard: MaterialCardView
    private lateinit var resultsList: RecyclerView
    private lateinit var stopAdapter: StopAdapter

    private var allStops: List<Stop> = emptyList()

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
        map.controller.setCenter(GeoPoint(59.3293, 18.0686)) // Stockholm city centre

        setupSearch()

        viewModel.stops.observe(this) { stops ->
            allStops = stops
            showStopsOnMap(stops)
        }
        viewModel.loadStops()
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

    private fun showStopsOnMap(stops: List<Stop>) {
        stops.forEach { stop ->
            val marker = Marker(map)
            marker.position = GeoPoint(stop.lat, stop.lon)
            marker.title = stop.name
            marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            marker.setOnMarkerClickListener { _, _ ->
                openCommuteConfig(stop)
                true
            }
            map.overlays.add(marker)
        }
        map.invalidate()
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
}
