package dev.fritze.skyward.core.sources

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
    )
}
