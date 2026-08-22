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
    fun picksTheLoosestThresholdAcrossRulesAndNestedGroupsThenAppliesTheSectionSixOneMargins() {
        val rules = listOf(
            ruleWith(Cond.KpAtLeast(6.0)),
            ruleWith(Cond.And(listOf(Cond.KpAtLeast(4.0), Cond.MagnitudeAtMost(3.0)))).copy(
                phenomena = setOf(Phenomenon.AURORA, Phenomenon.COMET),
            ),
            ruleWith(Cond.Or(listOf(Cond.Not(Cond.MagnitudeAtMost(1.0)), Cond.ReachableWithin(200.0, Quality.GOOD)))).copy(
                phenomena = setOf(Phenomenon.COMET),
            ),
            ruleWith(Cond.ReachableWithin(500.0, Quality.EXCELLENT)),
        )

        val thresholds = deriveThresholds(rules)

        // Lowest Kp any AURORA rule cares about (4.0) minus the §6.1 margin.
        assertEquals(3.0, thresholds.minKpOfInterest)
        // Faintest magnitude any COMET rule cares about (3.0) plus the §6.1 margin;
        // the Not(MagnitudeAtMost(1.0)) leaf is skipped, not folded in as 1.0.
        assertEquals(4.0, thresholds.maxCometMag)
        assertEquals(500.0, thresholds.maxTravelKm, "the largest travel radius any rule cares about")
    }

    @Test
    fun onlyCollectsKpFromRulesThatSeeAuroraAndMagnitudeFromRulesThatSeeComets() {
        val cometRuleWithKp = ruleWith(Cond.KpAtLeast(7.0)).copy(phenomena = setOf(Phenomenon.COMET))
        val auroraRuleWithMag = ruleWith(Cond.MagnitudeAtMost(2.0)).copy(phenomena = setOf(Phenomenon.AURORA))

        val thresholds = deriveThresholds(listOf(cometRuleWithKp, auroraRuleWithMag))

        assertNull(thresholds.minKpOfInterest, "no enabled AURORA rule has a KpAtLeast leaf")
        assertNull(thresholds.maxCometMag, "no enabled COMET rule has a MagnitudeAtMost leaf")
    }

    @Test
    fun aNegatedKpOrMagnitudeLeafIsSkippedRatherThanTreatedAsPositive() {
        // Not(KpAtLeast(6.0)) matches Kp < 6.0 down to zero, so it sets no
        // lower bound to prune by; folding it in as 6.0 would wrongly narrow
        // the threshold instead of widening it.
        val negatedKp = ruleWith(Cond.Not(Cond.KpAtLeast(6.0)))
        assertNull(deriveThresholds(listOf(negatedKp)).minKpOfInterest)

        val negatedMag = ruleWith(Cond.Not(Cond.MagnitudeAtMost(4.0))).copy(phenomena = setOf(Phenomenon.COMET))
        assertNull(deriveThresholds(listOf(negatedMag)).maxCometMag)

        // Alongside an un-negated leaf in another rule, the negated one still
        // contributes nothing — the margin applies only to the real leaf.
        val alsoPositive = ruleWith(Cond.KpAtLeast(5.0))
        assertEquals(4.0, deriveThresholds(listOf(negatedKp, alsoPositive)).minKpOfInterest)
    }

    @Test
    fun countsEveryRulePassedInBecauseFilteringIsTheCallersJob() {
        // deriveThresholds trusts its input list is already "enabled" rules
        // (the caller's job, per SourceRunner), so it ignores the flag itself.
        val disabled = ruleWith(Cond.KpAtLeast(6.0)).copy(enabled = false)
        assertEquals(5.0, deriveThresholds(listOf(disabled)).minKpOfInterest)
    }
}
