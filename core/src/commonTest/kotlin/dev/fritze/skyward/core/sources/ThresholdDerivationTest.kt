package dev.fritze.skyward.core.sources

import dev.fritze.skyward.core.model.Phenomenon
import dev.fritze.skyward.core.model.Quality
import dev.fritze.skyward.core.rules.Anchor
import dev.fritze.skyward.core.rules.Cond
import dev.fritze.skyward.core.rules.NotifySchedule
import dev.fritze.skyward.core.rules.Rule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class ThresholdDerivationTest {

    private val now = Instant.parse("2026-01-01T00:00:00Z")

    private fun ruleWith(condition: Cond) = Rule(
        id = "r", name = "n", enabled = true, phenomena = setOf(Phenomenon.AURORA), locationIds = null,
        condition = condition, schedule = NotifySchedule(emptyList(), Anchor.PEAK, false, null),
        createdAt = now, modifiedAt = now,
    )

    private fun terrestrialRuleWith(condition: Cond) =
        ruleWith(condition).copy(phenomena = setOf(Phenomenon.TERRESTRIAL))

    @Test
    fun emptyRuleListYieldsNoThresholds() {
        val thresholds = deriveThresholds(emptyList())
        assertNull(thresholds.minKpOfInterest)
        assertNull(thresholds.maxCometMag)
        assertNull(thresholds.maxTravelKm)
        assertFalse(thresholds.terrestrialRulesAreTravelBounded, "no terrestrial rule to bound")
    }

    @Test
    fun terrestrialRulesAreBoundedOnlyWhenEveryOneOfThemBoundsTravel() {
        val bounded = terrestrialRuleWith(
            Cond.And(listOf(Cond.EonetCategoryIn(setOf("volcanoes")), Cond.ReachableWithin(300.0))),
        )
        val unbounded = terrestrialRuleWith(Cond.EonetCategoryIn(setOf("wildfires")))

        assertTrue(deriveThresholds(listOf(bounded)).terrestrialRulesAreTravelBounded)
        assertFalse(deriveThresholds(listOf(bounded, unbounded)).terrestrialRulesAreTravelBounded)
        // A travel radius on some *other* phenomenon's rule bounds nothing
        // terrestrial (ADR 0008) — the reason this flag exists at all.
        val auroraWithRadius = ruleWith(Cond.ReachableWithin(500.0))
        val thresholds = deriveThresholds(listOf(unbounded, auroraWithRadius))
        assertEquals(500.0, thresholds.maxTravelKm)
        assertFalse(thresholds.terrestrialRulesAreTravelBounded)
    }

    @Test
    fun anOrBranchWithoutATravelBoundLeavesTheWholeRuleUnbounded() {
        // Either branch can be the one that matches, so a rule is bounded
        // only if every branch is; And needs just one bounded child.
        val orRule = terrestrialRuleWith(
            Cond.Or(listOf(Cond.ReachableWithin(300.0), Cond.EonetCategoryIn(setOf("volcanoes")))),
        )
        val andRule = terrestrialRuleWith(
            Cond.And(listOf(Cond.ReachableWithin(300.0), Cond.EonetCategoryIn(setOf("volcanoes")))),
        )
        val negatedRule = terrestrialRuleWith(Cond.Not(Cond.ReachableWithin(300.0)))

        assertFalse(deriveThresholds(listOf(orRule)).terrestrialRulesAreTravelBounded)
        assertTrue(deriveThresholds(listOf(andRule)).terrestrialRulesAreTravelBounded)
        assertFalse(deriveThresholds(listOf(negatedRule)).terrestrialRulesAreTravelBounded, "Not bounds nothing")
    }

    @Test
    fun picksTheLoosestThresholdAcrossRulesAndNestedGroups() {
        val rules = listOf(
            ruleWith(Cond.KpAtLeast(6.0)),
            ruleWith(Cond.And(listOf(Cond.KpAtLeast(4.0), Cond.MagnitudeAtMost(3.0)))),
            ruleWith(Cond.Or(listOf(Cond.Not(Cond.MagnitudeAtMost(5.0)), Cond.ReachableWithin(200.0, Quality.GOOD)))),
            ruleWith(Cond.ReachableWithin(500.0, Quality.EXCELLENT)),
        )

        val thresholds = deriveThresholds(rules)

        assertEquals(4.0, thresholds.minKpOfInterest, "the lowest Kp any rule cares about")
        assertEquals(5.0, thresholds.maxCometMag, "the faintest magnitude any rule cares about")
        assertEquals(500.0, thresholds.maxTravelKm, "the largest travel radius any rule cares about")
    }

    @Test
    fun countsEveryRulePassedInBecauseFilteringIsTheCallersJob() {
        // deriveThresholds trusts its input list is already "enabled" rules
        // (the caller's job, per SourceRunner), so it ignores the flag itself.
        val disabled = ruleWith(Cond.KpAtLeast(6.0)).copy(enabled = false)
        assertEquals(6.0, deriveThresholds(listOf(disabled)).minKpOfInterest)
    }
}
