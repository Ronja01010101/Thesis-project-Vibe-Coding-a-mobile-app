package com.example.thesisproject.model

import java.time.Instant

/**
 * A disruption / deviation message from the SL Deviations API
 * (`https://deviations.integration.sl.se/v1/messages`). Mirrors only the
 * fields we actually use; the API returns more (priority.influence_level,
 * priority.urgency_level, message_variants[].weblink, message_variants[].scope_alias,
 * stop_areas[].stop_points[], etc.) — those are dropped at the repository
 * boundary because the docs explicitly say `importance_level` is the only
 * priority field we should rely on, and the rest are not load-bearing for
 * the user-visible UI we're building in Step 7.
 *
 * Cross-system note (per Trafiklab docs research, see PLAN.md change log
 * entry for Step 7): the integer `affectedLineIds` and `affectedSiteIds`
 * here come from the SAME SL id namespace as `CommuteConfig.lineId` and
 * `CommuteConfig.stopId` — strong inference, not a verbatim docs guarantee.
 * The "SL Transport and GTFS-RT don't share IDs" caveat that drove BUG-009
 * does NOT apply between SL Transport and SL Deviations (both are SL
 * integration APIs).
 */
data class Deviation(
    /** Stable case-level id, used (with [version]) to dedupe across polls. */
    val deviationCaseId: Long?,
    /** Sequential message version. Required by the API. */
    val version: Int,
    /** Start of the validity window for this message version. Always present. */
    val publishFrom: Instant,
    /** End of validity. `null` = open-ended. */
    val publishUpto: Instant?,
    /**
     * Sort priority hint per docs ("only used to sort messages"). NO documented
     * enum or threshold — do NOT build "low / medium / high" UI tiers on this
     * value. May be missing.
     */
    val importanceLevel: Int?,
    /** One entry per language. `language` field carries the BCP-47-ish code (e.g. "sv"). */
    val variants: List<MessageVariant>,
    /** Affected line ids (SL line.id integers). Empty if the deviation is stop-area-only. */
    val affectedLineIds: List<Int>,
    /** Affected line designations (e.g. "57", "13X") — used to badge vehicle markers. */
    val affectedLineDesignations: List<String>,
    /** Affected site ids (SL site.id integers, same namespace as SL Transport). */
    val affectedSiteIds: List<Int>
) {
    /** Pick the variant matching [preferLanguage], else the first variant, else null. */
    fun preferredVariant(preferLanguage: String = "sv"): MessageVariant? =
        variants.firstOrNull { it.language.equals(preferLanguage, ignoreCase = true) }
            ?: variants.firstOrNull()
}

data class MessageVariant(
    val language: String,
    val header: String,
    val details: String
)
