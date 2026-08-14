package dev.fritze.skyward.core.sync

import dev.fritze.skyward.core.model.Certainty
import dev.fritze.skyward.core.model.GeoPoint
import dev.fritze.skyward.core.model.Phenomenon
import dev.fritze.skyward.core.model.SavedLocation
import dev.fritze.skyward.core.rules.Anchor
import dev.fritze.skyward.core.rules.Cond
import dev.fritze.skyward.core.rules.NotifySchedule
import dev.fritze.skyward.core.rules.Rule
import dev.fritze.skyward.core.rules.defaultRules
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Instant

class SyncCodecTest {

    private val now = Instant.parse("2026-01-01T00:00:00Z")
    private val loc = SavedLocation(id = "loc1", name = "Home", point = GeoPoint(52.0, 7.6), isPrimary = true, createdAt = now, modifiedAt = now)

    private fun rule(id: String = "r1", condition: Cond = Cond.CertaintyIs(Certainty.CERTAIN)) = Rule(
        id = id, name = "Test rule", enabled = true, phenomena = setOf(Phenomenon.AURORA),
        locationIds = null, condition = condition,
        schedule = NotifySchedule(emptyList(), Anchor.PEAK, false, null),
        createdAt = now, modifiedAt = now,
    )

    @Test
    fun exportThenParseRoundTrips() {
        val file = SyncFile(
            exportedAt = now,
            appVersion = "1.0.0",
            locations = listOf(loc),
            rules = defaultRules(now),
            settings = mapOf("horizon_years" to "3"),
            firedNotificationIds = listOf("se:20260812|1786556760|7200"),
        )
        val parsed = SyncCodec.parseForImport(SyncCodec.export(file))

        assertEquals(file.exportedAt, parsed.exportedAt)
        assertEquals(file.appVersion, parsed.appVersion)
        assertEquals(file.locations, parsed.locations)
        assertEquals(file.rules, parsed.rules)
        assertEquals(file.settings, parsed.settings)
        assertEquals(file.firedNotificationIds, parsed.firedNotificationIds)
        assertTrue(parsed.ruleWarnings.isEmpty())
    }

    @Test
    fun wrongFormatRefused() {
        val text = """{"format":"something-else","formatVersion":1,"exportedAt":"2026-01-01T00:00:00Z","appVersion":"1","locations":[],"rules":[],"settings":{},"firedNotificationIds":[]}"""
        assertFailsWith<SyncImportError.WrongFormat> { SyncCodec.parseForImport(text) }
    }

    @Test
    fun unknownFormatVersionRefused() {
        val text = """{"format":"skyward-sync","formatVersion":999,"exportedAt":"2026-01-01T00:00:00Z","appVersion":"1","locations":[],"rules":[],"settings":{},"firedNotificationIds":[]}"""
        assertFailsWith<SyncImportError.UnknownFormatVersion> { SyncCodec.parseForImport(text) }
    }

    @Test
    fun malformedJsonRefused() {
        assertFailsWith<SyncImportError.Malformed> { SyncCodec.parseForImport("not json at all") }
    }

    @Test
    fun unknownCondTypeImportsRuleDisabledWithWarningWithoutLosingOtherRules() {
        val goodRule = rule(id = "good", condition = Cond.VisibleAtLocation())
        val futureRule = rule(id = "future", condition = Cond.CertaintyIs(Certainty.CERTAIN))
        val file = SyncFile(
            exportedAt = now, appVersion = "9.9.9",
            locations = emptyList(), rules = listOf(goodRule, futureRule),
            settings = emptyMap(), firedNotificationIds = emptyList(),
        )
        // Simulate a newer app version's Cond subtype this version doesn't know about, without
        // hand-writing the whole rule's JSON: mutate just the discriminator for `futureRule`'s
        // condition (a distinctive, single-occurrence value: `certainty_is` with `FORECAST`).
        val exported = SyncCodec.export(file)
        val mutated = exported.replaceFirst("\"certainty_is\"", "\"some_future_condition\"")

        val parsed = SyncCodec.parseForImport(mutated)

        assertEquals(2, parsed.rules.size)
        val imported = parsed.rules.single { it.id == "future" }
        assertFalse(imported.enabled, "unrecognized condition -> rule imported disabled")
        assertEquals("Test rule", imported.name, "every other field should still decode")
        assertEquals(1, parsed.ruleWarnings.size)
        assertEquals("future", parsed.ruleWarnings.single().ruleId)

        val untouched = parsed.rules.single { it.id == "good" }
        assertEquals(goodRule, untouched, "an unrelated rule must not be affected")
    }
}
