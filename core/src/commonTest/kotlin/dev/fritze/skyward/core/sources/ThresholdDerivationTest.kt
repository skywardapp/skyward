package dev.fritze.skyward.core.sources

import dev.fritze.skyward.core.model.Phenomenon
import dev.fritze.skyward.core.model.Quality
import dev.fritze.skyward.core.rules.Anchor
import dev.fritze.skyward.core.rules.Cond
import dev.fritze.skyward.core.rules.NotifySchedule
import dev.fritze.skyward.core.rules.Rule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

class ThresholdDerivationTest {

    private val now = Instant.parse("2026-01-01T00:00:00Z")

    private fun ruleWith(condition: Cond) = Rule(
        id = "r", name = "n", enabled = true, phenomena = setOf(Phenomenon.AURORA), locationIds = null,
        condition = condition, schedule = NotifySchedule(emptyList(), Anchor.PEAK, false, null),
        createdAt = now, modifiedAt = now,
    )

    @Test
    fun emptyRuleListYieldsNoThresholds() {
        val thresholds = deriveThresholds(emptyList())
        assertNull(thresholds.minKpOfInterest)
        assertNull(thresholds.maxCometMag)
        assertNull(thresholds.maxTravelKm)
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
