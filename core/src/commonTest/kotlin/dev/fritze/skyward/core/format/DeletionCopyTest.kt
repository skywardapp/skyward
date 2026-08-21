package dev.fritze.skyward.core.format

import dev.fritze.skyward.core.model.Phenomenon
import dev.fritze.skyward.core.rules.Anchor
import dev.fritze.skyward.core.rules.Cond
import dev.fritze.skyward.core.rules.LocationDeletionImpact
import dev.fritze.skyward.core.rules.NotifySchedule
import dev.fritze.skyward.core.rules.Rule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

class DeletionCopyTest {

    private val now = Instant.parse("2026-01-01T00:00:00Z")

    private fun rule(name: String) = Rule(
        id = name, name = name, enabled = true, phenomena = setOf(Phenomenon.AURORA),
        locationIds = listOf("home"), condition = Cond.VisibleAtLocation(),
        schedule = NotifySchedule(emptyList(), Anchor.PEAK, false, null),
        createdAt = now, modifiedAt = now,
    )

    @Test
    fun `deleting a location with no rules attached says only what it removes`() {
        val copy = deleteLocationConfirmation("Home", LocationDeletionImpact(narrowed = emptyList(), stranded = emptyList()))
        assertEquals("Delete this location?", copy.title)
        assertEquals("\"Home\" will be removed and any reminders for it cancelled.", copy.body)
    }

    @Test
    fun `stranded rules are named, so the invisible damage is visible`() {
        val copy = deleteLocationConfirmation(
            "Home",
            LocationDeletionImpact(narrowed = emptyList(), stranded = listOf(rule("Aurora watch"))),
        )
        assertTrue("One rule names only \"Home\"" in copy.body, copy.body)
        assertTrue("\"Aurora watch\"" in copy.body, copy.body)
    }

    @Test
    fun `narrowed rules are reported as surviving, not as losses`() {
        val copy = deleteLocationConfirmation(
            "Home",
            LocationDeletionImpact(narrowed = listOf(rule("Eclipses"), rule("Comets")), stranded = emptyList()),
        )
        assertTrue("2 other rules keep working" in copy.body, copy.body)
        assertFalse("turned off" in copy.body, copy.body)
    }

    @Test
    fun `a long list of rules is summarised rather than dumped`() {
        val many = (1..9).map { rule("Rule $it") }
        val body = deleteLocationConfirmation("Home", LocationDeletionImpact(emptyList(), many)).body
        assertTrue("\"Rule 5\"" in body, body)
        assertFalse("\"Rule 6\"" in body, body)
        assertTrue("and 4 more" in body, body)
    }

    @Test
    fun `an unnamed rule still reads as a rule`() {
        val body = deleteLocationConfirmation("Home", LocationDeletionImpact(emptyList(), listOf(rule("")))).body
        assertTrue("\"Untitled rule\"" in body, body)
    }

    @Test
    fun `rule deletion copy names the rule and its consequence`() {
        val copy = deleteRuleConfirmation("Aurora watch")
        assertEquals("Delete this rule?", copy.title)
        assertTrue("\"Aurora watch\"" in copy.body, copy.body)
        assertTrue("reminders will be cancelled" in copy.body, copy.body)
    }
}
