package dev.fritze.skyward.core.rules

import dev.fritze.skyward.core.model.Phenomenon
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * §9.1's `locationIds` are ids with no foreign key behind them, so deleting a
 * location is where dangling references get made. These pin down what
 * survives the delete.
 */
class LocationReferencesTest {

    private val created = Instant.parse("2026-01-01T00:00:00Z")
    private val deletedAt = Instant.parse("2026-06-01T12:00:00Z")

    private fun rule(id: String, locationIds: List<String>?, enabled: Boolean = true) = Rule(
        id = id, name = "rule $id", enabled = enabled, phenomena = setOf(Phenomenon.AURORA),
        locationIds = locationIds, condition = Cond.VisibleAtLocation(),
        schedule = NotifySchedule(emptyList(), Anchor.PEAK, false, null),
        createdAt = created, modifiedAt = created,
    )

    @Test
    fun `a rule for all locations is not affected`() {
        val rules = listOf(rule("r1", locationIds = null))
        assertTrue(locationDeletionImpact("home", rules).isEmpty)
        assertEquals(emptyList(), rulesAfterLocationDeletion("home", rules, deletedAt))
    }

    @Test
    fun `a rule naming other locations only is not affected`() {
        val rules = listOf(rule("r1", listOf("cabin", "office")))
        assertTrue(locationDeletionImpact("home", rules).isEmpty)
    }

    @Test
    fun `a rule naming this location among others is narrowed, not disabled`() {
        val rules = listOf(rule("r1", listOf("home", "cabin")))
        val impact = locationDeletionImpact("home", rules)
        assertEquals(listOf("r1"), impact.narrowed.map { it.id })
        assertTrue(impact.stranded.isEmpty())

        val rewritten = rulesAfterLocationDeletion("home", rules, deletedAt).single()
        assertEquals(listOf("cabin"), rewritten.locationIds)
        assertTrue(rewritten.enabled, "it can still match from the cabin")
        assertEquals(deletedAt, rewritten.modifiedAt)
    }

    @Test
    fun `a rule naming only this location is stranded and switched off`() {
        val rules = listOf(rule("r1", listOf("home")))
        val impact = locationDeletionImpact("home", rules)
        assertEquals(listOf("r1"), impact.stranded.map { it.id })

        val rewritten = rulesAfterLocationDeletion("home", rules, deletedAt).single()
        assertFalse(rewritten.enabled, "a rule with nowhere to match must not sit in the list looking healthy")
        // Emptied, not widened to null: null means "all saved locations",
        // which would start firing for places the user never picked. Empty is
        // also the state RuleEditorScreen refuses to save, so opening the rule
        // explains itself.
        assertEquals(emptyList(), rewritten.locationIds)
    }

    @Test
    fun `only the rules that change are returned`() {
        val rules = listOf(
            rule("all", locationIds = null),
            rule("elsewhere", listOf("cabin")),
            rule("both", listOf("home", "cabin")),
            rule("only", listOf("home")),
        )
        assertEquals(setOf("both", "only"), rulesAfterLocationDeletion("home", rules, deletedAt).map { it.id }.toSet())
    }

    @Test
    fun `an already-disabled stranded rule is not re-enabled`() {
        val rules = listOf(rule("r1", listOf("home"), enabled = false))
        assertFalse(rulesAfterLocationDeletion("home", rules, deletedAt).single().enabled)
    }
}
