package dev.fritze.skyward.core.sources

import dev.fritze.skyward.core.model.Phenomenon
import dev.fritze.skyward.core.rules.Cond
import dev.fritze.skyward.core.rules.Rule

/**
 * §6.1: scans every enabled rule's condition tree for the thresholds POLLED
 * sources can use to avoid fetching/tracking data no rule could ever match
 * — the loosest (most permissive) value across all rules for each kind,
 * since any one of them matching is enough. `null` means no enabled rule
 * uses that condition type at all, i.e. no threshold to prune by.
 *
 * Kp and magnitude leaves are collected only from rules whose `phenomena`
 * include AURORA / COMET respectively — a `KpAtLeast` inside a rule that
 * never sees aurora occurrences says nothing about aurora thresholds.
 * `ReachableWithin` is collected across all enabled rules regardless of
 * phenomenon, per §6.1.
 *
 * A leaf under `Not` is skipped rather than collected: `Not(KpAtLeast(6.0))`
 * matches Kp < 6.0 down to zero, so it establishes no lower bound to prune
 * by, and folding it in as if positive would wrongly narrow the threshold.
 */
fun deriveThresholds(enabledRules: List<Rule>): DerivedThresholds {
    fun collectKp(cond: Cond): List<Double> = when (cond) {
        is Cond.And -> cond.all.flatMap(::collectKp)
        is Cond.Or -> cond.any.flatMap(::collectKp)
        is Cond.Not -> emptyList()
        is Cond.KpAtLeast -> listOf(cond.kp)
        else -> emptyList()
    }

    fun collectMag(cond: Cond): List<Double> = when (cond) {
        is Cond.And -> cond.all.flatMap(::collectMag)
        is Cond.Or -> cond.any.flatMap(::collectMag)
        is Cond.Not -> emptyList()
        is Cond.MagnitudeAtMost -> listOf(cond.mag)
        else -> emptyList()
    }

    fun collectKm(cond: Cond): List<Double> = when (cond) {
        is Cond.And -> cond.all.flatMap(::collectKm)
        is Cond.Or -> cond.any.flatMap(::collectKm)
        is Cond.Not -> emptyList()
        is Cond.ReachableWithin -> listOf(cond.km)
        else -> emptyList()
    }

    val kps = enabledRules.filter { Phenomenon.AURORA in it.phenomena }.flatMap { collectKp(it.condition) }
    val mags = enabledRules.filter { Phenomenon.COMET in it.phenomena }.flatMap { collectMag(it.condition) }
    val kms = enabledRules.flatMap { collectKm(it.condition) }

    return DerivedThresholds(
        minKpOfInterest = kps.minOrNull()?.minus(1.0),
        maxCometMag = mags.maxOrNull()?.plus(1.0),
        maxTravelKm = kms.maxOrNull(),
        terrestrialRulesAreTravelBounded = terrestrialRulesAreTravelBounded(enabledRules),
    )
}

/**
 * Whether narrowing EONET's response to a padded box around the saved
 * locations can cost a match (§7.7, ADR 0008): it can't if every enabled
 * rule that sees terrestrial occurrences already refuses to match beyond
 * some distance. A user with no terrestrial rules at all gets `false` —
 * there is no rule-derived distance to trust, and the events would only be
 * feeding the browsable list.
 */
private fun terrestrialRulesAreTravelBounded(enabledRules: List<Rule>): Boolean {
    val terrestrialRules = enabledRules.filter { Phenomenon.TERRESTRIAL in it.phenomena }
    return terrestrialRules.isNotEmpty() && terrestrialRules.all { travelBoundKm(it.condition) != null }
}

/**
 * An upper bound on `travelDistanceKm` that [cond] being true implies, or
 * `null` if it implies none.
 *
 * `ReachableWithin` is also satisfied by an occurrence visible at the
 * location itself, but `TerrestrialVisibilityModel` never reports one
 * (§8.8), so against terrestrial occurrences it is a pure distance bound.
 * `And` takes the tightest of its children's bounds (all must hold), `Or`
 * the loosest and only if every branch has one, and `Not` is treated as
 * unbounded — negating a distance bound doesn't produce another one.
 */
private fun travelBoundKm(cond: Cond): Double? = when (cond) {
    is Cond.ReachableWithin -> cond.km
    is Cond.And -> cond.all.mapNotNull(::travelBoundKm).minOrNull()
    is Cond.Or -> cond.any.map(::travelBoundKm).let { bounds ->
        if (bounds.any { it == null }) null else bounds.filterNotNull().maxOrNull()
    }
    else -> null
}
