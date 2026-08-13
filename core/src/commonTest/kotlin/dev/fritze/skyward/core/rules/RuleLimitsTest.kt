package dev.fritze.skyward.core.rules

import dev.fritze.skyward.core.model.Certainty
import dev.fritze.skyward.core.model.Phenomenon
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Instant

class RuleLimitsTest {

    private val now = Instant.parse("2026-01-01T00:00:00Z")

    private fun ruleWith(condition: Cond) = Rule(
        id = "r", name = "n", enabled = true, phenomena = setOf(Phenomenon.SOLAR_ECLIPSE),
        locationIds = null, condition = condition,
        schedule = NotifySchedule(emptyList(), Anchor.PEAK, false, null),
        createdAt = now, modifiedAt = now,
    )

    @Test
    fun aShallowSmallTreePassesCleanly() {
        val cond = Cond.And(listOf(Cond.CertaintyIs(Certainty.CERTAIN), Cond.VisibleAtLocation()))
        assertTrue(RuleLimits.violations(ruleWith(cond)).isEmpty())
    }

    @Test
    fun aDeepChainOfNotsExceedsTheDepthLimit() {
        var cond: Cond = Cond.CertaintyIs(Certainty.CERTAIN)
        repeat(RuleLimits.MAX_TREE_DEPTH + 2) { cond = Cond.Not(cond) }
        val violations = RuleLimits.violations(ruleWith(cond))
        assertTrue(violations.any { it.contains("depth") }, "expected a depth violation, got $violations")
    }

    @Test
    fun aWideAndExceedsTheNodeCountLimit() {
        val children = (1..RuleLimits.MAX_NODES_PER_RULE + 5).map { Cond.CertaintyIs(Certainty.CERTAIN) as Cond }
        val cond = Cond.And(children)
        val violations = RuleLimits.violations(ruleWith(cond))
        assertTrue(violations.any { it.contains("nodes") }, "expected a node-count violation, got $violations")
    }

    @Test
    fun allDefaultRulesAreWithinLimits() {
        for (rule in defaultRules(now)) {
            assertTrue(RuleLimits.violations(rule).isEmpty(), "default rule '${rule.name}' violates limits: ${RuleLimits.violations(rule)}")
        }
    }
}
