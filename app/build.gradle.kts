import com.google.gson.GsonBuilder
import org.apache.commons.csv.CSVFormat
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.Properties
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream
import java.util.zip.ZipInputStream

buildscript {
    repositories {
        mavenCentral()
    }
    dependencies {
        classpath("com.google.code.gson:gson:2.11.0")
        classpath("org.apache.commons:commons-csv:1.11.0")
    }
}

plugins {
    alias(libs.plugins.android.application)
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

android {
    namespace = "com.example.thesisproject"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.example.thesisproject"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // SL Transport, Deviations, Journey Planner need no key (open access)
        buildConfigField("String", "GTFS_REALTIME_KEY", "\"${localProperties["GTFS_REALTIME_KEY"] ?: ""}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    implementation(libs.osmdroid)
    implementation(libs.retrofit)
    implementation(libs.retrofit.gson)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.preference)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}

// =====================================================================
// Step 4a: GTFS extraction task
//
// Run rarely on a developer machine: ./gradlew extractGtfs
// Downloads sl.zip from Trafiklab GTFS Regional Static, extracts a compact
// per-line JSON (route geometry + ordered stop sequences) to
// app/src/main/assets/sl-lines.json which the app reads at startup.
// The user's device never downloads or parses GTFS itself.
// =====================================================================

data class StopExport(val id: String, val name: String, val lat: Double, val lon: Double)

data class DirectionExport(
    val directionId: Int,
    val headsign: String,
    val stops: List<StopExport>,
    val polyline: List<List<Double>>
)

data class LineExport(
    val lineDesignation: String,
    val routeId: String,
    val routeType: Int,
    val directions: List<DirectionExport>
)

data class GtfsExport(
    val generatedAt: String,
    val feedSource: String,
    val lineCount: Int,
    val lines: List<LineExport>
)

// Internal-only data carriers (hoisted to top level so the .kts compiler
// doesn't choke on locally-declared data classes inside lambdas).
data class GtfsRoute(val shortName: String, val type: Int)

data class GtfsRepTrip(
    val tripId: String,
    val shapeId: String?,
    val headsign: String,
    val routeId: String,
    val directionId: Int
)

fun openGtfsCsv(file: java.io.File) = CSVFormat.DEFAULT.builder()
    .setHeader()
    .setSkipHeaderRecord(true)
    .build()
    .parse(BufferedReader(InputStreamReader(file.inputStream(), StandardCharsets.UTF_8)))

// Round to 5 decimal places (~1.1m accuracy, plenty for transit visualisation)
// to keep the JSON asset compact.
fun round5(d: Double): Double = Math.round(d * 100000.0) / 100000.0

tasks.register("extractGtfs") {
    group = "build setup"
    description = "Downloads SL GTFS Static feed and extracts a compact per-line JSON to assets."

    doLast {
        val key = (localProperties["GTFS_STATIC_KEY"] as? String).orEmpty()
        check(key.isNotBlank()) { "GTFS_STATIC_KEY missing in local.properties" }

        val cacheDir = rootProject.layout.buildDirectory.dir("gtfs-cache").get().asFile
        cacheDir.mkdirs()
        val zipFile = cacheDir.resolve("sl.zip")
        val extractDir = cacheDir.resolve("extract")

        val sevenDaysMs = 7L * 24 * 60 * 60 * 1000
        val cacheStale = !zipFile.exists() ||
            (System.currentTimeMillis() - zipFile.lastModified()) > sevenDaysMs

        if (cacheStale) {
            logger.lifecycle("Downloading sl.zip from Trafiklab...")
            val url = URI.create("https://opendata.samtrafiken.se/gtfs/sl/sl.zip?key=$key").toURL()
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/zip, application/octet-stream, */*")
            // Trafiklab's open-data endpoint REQUIRES this header. Their server returns
            // Content-Encoding: identity for the zip itself, but rejects clients that
            // don't advertise compression support.
            connection.setRequestProperty("Accept-Encoding", "gzip, deflate")
            connection.setRequestProperty("User-Agent", "thesis-project-android-extractGtfs/1.0")
            connection.connectTimeout = 30_000
            connection.readTimeout = 120_000
            try {
                val code = connection.responseCode
                if (code != 200) {
                    val errorBody = connection.errorStream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }
                    error("Trafiklab returned HTTP $code (key length=${key.length}). Response body: ${errorBody?.take(500) ?: "(empty)"}")
                }
                val raw = connection.inputStream
                val decoded = when (connection.contentEncoding) {
                    "gzip" -> GZIPInputStream(raw)
                    "deflate" -> InflaterInputStream(raw)
                    else -> raw
                }
                decoded.use { input ->
                    Files.copy(input, zipFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            } finally {
                connection.disconnect()
            }
            logger.lifecycle("Downloaded ${zipFile.length() / 1024 / 1024} MB to ${zipFile.absolutePath}")
        } else {
            logger.lifecycle("Using cached sl.zip (${zipFile.length() / 1024 / 1024} MB, " +
                "${(System.currentTimeMillis() - zipFile.lastModified()) / (1000 * 60 * 60)}h old)")
        }

        if (extractDir.exists()) extractDir.deleteRecursively()
        extractDir.mkdirs()
        ZipInputStream(BufferedInputStream(zipFile.inputStream())).use { zin ->
            var entry = zin.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val outFile = extractDir.resolve(entry.name)
                    outFile.parentFile?.mkdirs()
                    outFile.outputStream().use { zin.copyTo(it) }
                }
                entry = zin.nextEntry
            }
        }
        logger.lifecycle("Extracted GTFS to ${extractDir.absolutePath}")

        // 1) routes.txt: load all routes (small)
        val routesById = mutableMapOf<String, GtfsRoute>()
        openGtfsCsv(extractDir.resolve("routes.txt")).use { parser ->
            parser.forEach { r ->
                routesById[r.get("route_id")] = GtfsRoute(
                    shortName = r.get("route_short_name").orEmpty(),
                    type = r.get("route_type")?.toIntOrNull() ?: -1
                )
            }
        }
        logger.lifecycle("Loaded ${routesById.size} routes")

        // 2) trips.txt: pick ONE representative trip per (route_id, direction_id)
        val representativeByKey = mutableMapOf<Pair<String, Int>, GtfsRepTrip>()
        openGtfsCsv(extractDir.resolve("trips.txt")).use { parser ->
            parser.forEach { r ->
                val routeId = r.get("route_id")
                val directionId = r.get("direction_id")?.toIntOrNull() ?: 0
                val k = routeId to directionId
                if (k !in representativeByKey) {
                    representativeByKey[k] = GtfsRepTrip(
                        tripId = r.get("trip_id"),
                        shapeId = r.get("shape_id").takeUnless { it.isNullOrBlank() },
                        headsign = r.get("trip_headsign").orEmpty(),
                        routeId = routeId,
                        directionId = directionId
                    )
                }
            }
        }
        logger.lifecycle("Selected ${representativeByKey.size} representative trips")

        val neededTripIds = representativeByKey.values.map { it.tripId }.toHashSet()
        val neededShapeIds = representativeByKey.values.mapNotNull { it.shapeId }.toHashSet()

        // 3) stops.txt: load all stops (medium, ~14k)
        val stopsById = mutableMapOf<String, StopExport>()
        openGtfsCsv(extractDir.resolve("stops.txt")).use { parser ->
            parser.forEach { r ->
                val id = r.get("stop_id")
                val lat = r.get("stop_lat")?.toDoubleOrNull()
                val lon = r.get("stop_lon")?.toDoubleOrNull()
                if (lat != null && lon != null) {
                    stopsById[id] = StopExport(
                        id = id,
                        name = r.get("stop_name").orEmpty(),
                        lat = round5(lat),
                        lon = round5(lon)
                    )
                }
            }
        }
        logger.lifecycle("Loaded ${stopsById.size} stops")

        // 4) shapes.txt: stream and keep only the shape_ids we need
        val rawShapes = mutableMapOf<String, MutableList<Triple<Int, Double, Double>>>()
        openGtfsCsv(extractDir.resolve("shapes.txt")).use { parser ->
            parser.forEach { r ->
                val shapeId = r.get("shape_id")
                if (shapeId in neededShapeIds) {
                    val seq = r.get("shape_pt_sequence")?.toIntOrNull() ?: return@forEach
                    val lat = r.get("shape_pt_lat")?.toDoubleOrNull() ?: return@forEach
                    val lon = r.get("shape_pt_lon")?.toDoubleOrNull() ?: return@forEach
                    rawShapes.getOrPut(shapeId) { mutableListOf() }.add(Triple(seq, lat, lon))
                }
            }
        }
        val shapesById: Map<String, List<List<Double>>> = rawShapes.mapValues { (_, points) ->
            points.sortedBy { it.first }.map { listOf(round5(it.second), round5(it.third)) }
        }
        logger.lifecycle("Loaded ${shapesById.size} shape polylines")

        // 5) stop_times.txt: stream the giant file, keep only rows for our representative trips
        val rawStopSequences = mutableMapOf<String, MutableList<Pair<Int, String>>>()
        openGtfsCsv(extractDir.resolve("stop_times.txt")).use { parser ->
            parser.forEach { r ->
                val tripId = r.get("trip_id")
                if (tripId in neededTripIds) {
                    val seq = r.get("stop_sequence")?.toIntOrNull() ?: return@forEach
                    val stopId = r.get("stop_id")
                    rawStopSequences.getOrPut(tripId) { mutableListOf() }.add(seq to stopId)
                }
            }
        }
        val stopSequencesByTrip: Map<String, List<String>> = rawStopSequences.mapValues { (_, list) ->
            list.sortedBy { it.first }.map { it.second }
        }
        logger.lifecycle("Loaded stop sequences for ${stopSequencesByTrip.size} trips")

        // 6) Build the export model
        val byRouteId = representativeByKey.values.groupBy { it.routeId }
        val lines = byRouteId.mapNotNull { (routeId, trips) ->
            val route = routesById[routeId] ?: return@mapNotNull null
            val directions = trips.mapNotNull { trip ->
                val stopIds = stopSequencesByTrip[trip.tripId] ?: return@mapNotNull null
                val stops = stopIds.mapNotNull { stopsById[it] }
                val polyline = trip.shapeId?.let { shapesById[it] }.orEmpty()
                DirectionExport(
                    directionId = trip.directionId,
                    headsign = trip.headsign,
                    stops = stops,
                    polyline = polyline
                )
            }
            LineExport(
                lineDesignation = route.shortName,
                routeId = routeId,
                routeType = route.type,
                directions = directions.sortedBy { it.directionId }
            )
        }.sortedWith(compareBy({ it.routeType }, { it.lineDesignation }))

        val export = GtfsExport(
            generatedAt = Instant.now().toString(),
            feedSource = "Trafiklab GTFS Regional Static (sl.zip)",
            lineCount = lines.size,
            lines = lines
        )

        // 7) Write JSON to assets
        val outDir = project.layout.projectDirectory.dir("src/main/assets").asFile
        outDir.mkdirs()
        val outFile = outDir.resolve("sl-lines.json")
        // Compact JSON (no pretty-printing) — pretty-printed polylines balloon the file.
        // The asset is machine-read at app startup; humans can pretty-print on demand.
        val gson = GsonBuilder().disableHtmlEscaping().create()
        Files.newBufferedWriter(outFile.toPath(), StandardCharsets.UTF_8).use { writer ->
            gson.toJson(export, writer)
            writer.write("\n")
        }
        logger.lifecycle("Wrote ${lines.size} lines to ${outFile.absolutePath} " +
            "(${outFile.length() / 1024} KB)")
    }
}
