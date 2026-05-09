package com.example.thesisproject.model

/**
 * A single GTFS Realtime ServiceAlert that carries at least one
 * `informed_entity.trip.trip_id` selector. We surface these separately
 * from line-level [Deviation]s (from the SL Deviations API) because a
 * trip alert tells us "this affects YOUR specific bus" — much more
 * precise than the line-level disruption surface SL Deviations gives.
 *
 * Empirical finding (smoke-test 2026-05-09): ~15% of informed_entity
 * selectors in `ServiceAlerts.pb` populate `trip.trip_id`, and the
 * trip_ids match the same scheme as VehiclePositions, so direct
 * matching against tracked trip_ids works without any bridge.
 *
 * Cause / effect carry the GTFS-RT enum names verbatim
 * (e.g. CONSTRUCTION, TECHNICAL_PROBLEM, NO_SERVICE) — useful for
 * future per-cause visual treatment but not load-bearing today.
 */
data class TripAlert(
    /** Stable per-feed entity id (used for dedup across polls). */
    val id: String,
    /** Header text (Swedish preferred, falls back to first translation). */
    val header: String,
    /** Detailed description if present, else empty. */
    val description: String,
    /** GTFS-RT cause enum name, or null if absent. */
    val cause: String?,
    /** GTFS-RT effect enum name, or null if absent. */
    val effect: String?,
    /** All trip_ids referenced by this alert's informed_entity selectors. */
    val tripIds: Set<String>
)
