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
 */
fun deriveThresholds(enabledRules: List<Rule>): DerivedThresholds {
    val kps = mutableListOf<Double>()
    val mags = mutableListOf<Double>()
    val kms = mutableListOf<Double>()

    fun walk(cond: Cond) {
        when (cond) {
            is Cond.And -> cond.all.forEach(::walk)
            is Cond.Or -> cond.any.forEach(::walk)
            is Cond.Not -> walk(cond.inner)
            is Cond.KpAtLeast -> kps += cond.kp
            is Cond.MagnitudeAtMost -> mags += cond.mag
            is Cond.ReachableWithin -> kms += cond.km
            else -> Unit
        }
    }
    enabledRules.forEach { walk(it.condition) }

    return DerivedThresholds(
        minKpOfInterest = kps.minOrNull(),
        maxCometMag = mags.maxOrNull(),
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
