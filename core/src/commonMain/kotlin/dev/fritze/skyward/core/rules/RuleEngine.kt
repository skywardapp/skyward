package dev.fritze.skyward.core.rules

import dev.fritze.skyward.core.model.AuroraPayload
import dev.fritze.skyward.core.model.CometPayload
import dev.fritze.skyward.core.model.LunarEclipsePayload
import dev.fritze.skyward.core.model.MeteorShowerPayload
import dev.fritze.skyward.core.model.Occurrence
import dev.fritze.skyward.core.model.SavedLocation
import dev.fritze.skyward.core.model.SolarEclipsePayload
import dev.fritze.skyward.core.model.TerrestrialPayload
import dev.fritze.skyward.core.model.VisibilityResult
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/** §9.2 evaluation semantics. Stateless — visibility results are computed and cached by the caller. */
object RuleEngine {

    /**
     * True iff [rule] fires for (occ, loc): [rule] is enabled, sees
     * `occ.phenomenon`, applies to `loc` (or all locations), and its
     * condition tree evaluates true against the pre-computed [visres].
     */
    fun matches(rule: Rule, occ: Occurrence, loc: SavedLocation, visres: VisibilityResult, now: Instant): Boolean {
        if (!rule.enabled) return false
        if (occ.phenomenon !in rule.phenomena) return false
        if (rule.locationIds != null && loc.id !in rule.locationIds) return false
        return evaluate(rule.condition, occ, loc, visres, now)
    }

    /** Missing-field conditions (e.g. [Cond.KpAtLeast] on a non-aurora occurrence) are `false`, never errors (§9.2). */
    fun evaluate(cond: Cond, occ: Occurrence, loc: SavedLocation, visres: VisibilityResult, now: Instant): Boolean {
        fun rec(c: Cond) = evaluate(c, occ, loc, visres, now)
        return when (cond) {
            is Cond.And -> cond.all.all(::rec)
            is Cond.Or -> cond.any.any(::rec)
            is Cond.Not -> !rec(cond.inner)

            is Cond.VisibleAtLocation -> visres.visibleAtLocation && visres.quality >= cond.minQuality
            is Cond.ReachableWithin -> {
                val locallyGood = visres.visibleAtLocation && visres.quality >= cond.minQualityThere
                val travelKm = visres.travelDistanceKm
                val travelGood = travelKm != null && travelKm <= cond.km &&
                    (visres.qualityAtNearestPoint?.let { it >= cond.minQualityThere } ?: false)
                locallyGood || travelGood
            }

            is Cond.KpAtLeast -> (occ.payload as? AuroraPayload)?.let { it.kpForecast >= cond.kp } ?: false
            is Cond.ZhrAtLeast -> (occ.payload as? MeteorShowerPayload)?.let { (it.zhr ?: 0) >= cond.zhr } ?: false
            is Cond.MagnitudeAtMost -> (occ.payload as? CometPayload)?.let { it.peakMag <= cond.mag } ?: false
            is Cond.EclipseKindIn -> (occ.payload as? SolarEclipsePayload)?.let { it.kind in cond.kinds } ?: false
            is Cond.LunarKindIn -> (occ.payload as? LunarEclipsePayload)?.let { it.kind in cond.kinds } ?: false
            is Cond.MoonIlluminationAtMost ->
                (occ.payload as? MeteorShowerPayload)?.let { it.moonIlluminationAtPeak <= cond.fraction } ?: false
            is Cond.EonetCategoryIn -> (occ.payload as? TerrestrialPayload)?.let { it.categoryId in cond.categoryIds } ?: false
            is Cond.CertaintyIs -> occ.certainty == cond.certainty
            is Cond.AuroraKindIs -> (occ.payload as? AuroraPayload)?.let { it.forecastKind == cond.kind } ?: false
            is Cond.OccurrenceIdIs -> occ.id == cond.id

            is Cond.PeakInDaysAhead -> occ.peakTime?.let { it >= now && (it - now) <= cond.maxDays.days } ?: false
            is Cond.PeakOnWeekend -> occ.peakTime?.let {
                isLocalWeekend(approximateLocalDateTime(it, loc.point.lonDeg), cond.includeFridayNight)
            } ?: false
            is Cond.PeakInLocalHours -> occ.peakTime?.let {
                isInLocalHourRange(approximateLocalDateTime(it, loc.point.lonDeg), cond.fromHour, cond.toHour)
            } ?: false
        }
    }
}

/** §9.5: cap: 100 rules, tree depth <= 8, <= 50 nodes per rule — enforced at save time. */
object RuleLimits {
    const val MAX_RULES = 100
    const val MAX_TREE_DEPTH = 8
    const val MAX_NODES_PER_RULE = 50

    fun violations(rule: Rule): List<String> {
        val violations = mutableListOf<String>()
        val depth = depthOf(rule.condition)
        val nodes = nodeCountOf(rule.condition)
        if (depth > MAX_TREE_DEPTH) violations += "condition tree depth $depth exceeds max $MAX_TREE_DEPTH"
        if (nodes > MAX_NODES_PER_RULE) violations += "condition tree has $nodes nodes, exceeds max $MAX_NODES_PER_RULE"
        return violations
    }

    /** Save-time cap on the whole rule set, in addition to each rule's own [violations]. */
    fun violations(rules: List<Rule>): List<String> =
        if (rules.size > MAX_RULES) listOf("rule set has ${rules.size} rules, exceeds max $MAX_RULES") else emptyList()

    private fun depthOf(cond: Cond): Int = when (cond) {
        is Cond.And -> 1 + (cond.all.maxOfOrNull(::depthOf) ?: 0)
        is Cond.Or -> 1 + (cond.any.maxOfOrNull(::depthOf) ?: 0)
        is Cond.Not -> 1 + depthOf(cond.inner)
        else -> 1
    }

    private fun nodeCountOf(cond: Cond): Int = when (cond) {
        is Cond.And -> 1 + cond.all.sumOf(::nodeCountOf)
        is Cond.Or -> 1 + cond.any.sumOf(::nodeCountOf)
        is Cond.Not -> 1 + nodeCountOf(cond.inner)
        else -> 1
    }
}
