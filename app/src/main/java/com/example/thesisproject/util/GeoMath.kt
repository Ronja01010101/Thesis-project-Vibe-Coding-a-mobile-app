package com.example.thesisproject.util

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pure geometry helpers used by the widget data path. No Android types so
 * these are testable in plain JVM unit tests.
 */
object GeoMath {

    private const val EARTH_RADIUS_M = 6_371_000.0

    /**
     * Great-circle distance between two (lat, lon) pairs, in metres. Standard
     * haversine — sub-metre accurate at the distances that matter for transit
     * (≤ 50 km between any two points on a typical commute).
     */
    fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val sinLatHalf = sin(dLat / 2)
        val sinLonHalf = sin(dLon / 2)
        val a = sinLatHalf * sinLatHalf +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sinLonHalf * sinLonHalf
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return EARTH_RADIUS_M * c
    }

    /**
     * Project the point (lat, lon) onto the line segment from (latA, lonA) to
     * (latB, lonB). Returns t in [0, 1] such that the projection point is at
     * (latA + t * (latB - latA), lonA + t * (lonB - lonA)).
     *
     * Uses an equirectangular approximation around the segment's midpoint —
     * fine for transit-segment lengths (typically < 1 km between consecutive
     * stops); the error vs. true geodesic projection is sub-metre at this
     * scale and won't visibly affect the widget's route-line gauge.
     */
    fun projectOntoSegment(
        lat: Double, lon: Double,
        latA: Double, lonA: Double,
        latB: Double, lonB: Double
    ): Double {
        val midLat = (latA + latB) / 2
        val cosLat = cos(Math.toRadians(midLat))
        val ax = lonA * cosLat
        val ay = latA
        val bx = lonB * cosLat
        val by = latB
        val px = lon * cosLat
        val py = lat
        val dx = bx - ax
        val dy = by - ay
        val lenSq = dx * dx + dy * dy
        if (lenSq == 0.0) return 0.0
        val t = ((px - ax) * dx + (py - ay) * dy) / lenSq
        return max(0.0, min(1.0, t))
    }
}
