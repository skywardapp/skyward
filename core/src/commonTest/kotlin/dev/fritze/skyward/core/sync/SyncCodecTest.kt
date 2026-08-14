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
        assertTrue(parsed.degradedRuleIds.isEmpty())
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
    fun malformedFieldShapesRefuseWithoutThrowingRaw() {
        // "format" as an array, not a string -- must not escape as a raw ClassCastException/
        // IllegalArgumentException from the underlying JsonPrimitive access.
        val text = """{"format":["not","a","string"],"formatVersion":1,"exportedAt":"2026-01-01T00:00:00Z","appVersion":"1","locations":[],"rules":[],"settings":{},"firedNotificationIds":[]}"""
        assertFailsWith<SyncImportError.WrongFormat> { SyncCodec.parseForImport(text) }
    }

    @Test
    fun ruleWithNonPrimitiveIdStillWarnsInsteadOfThrowing() {
        val file = SyncFile(
            exportedAt = now, appVersion = "9.9.9",
            locations = emptyList(), rules = listOf(rule(id = "r1")),
            settings = emptyMap(), firedNotificationIds = emptyList(),
        )
        val exported = SyncCodec.export(file)
        // "id" is a non-primitive: both Rule and RuleSkeleton decode `id: String`, so this fails
        // both attempts and exercises the raw scrape-for-a-warning path (which must not throw).
        val mutated = exported.replace("\"id\":\"r1\"", "\"id\":{\"nested\":true}")

        val parsed = SyncCodec.parseForImport(mutated)

        assertTrue(parsed.rules.isEmpty())
        assertEquals(1, parsed.ruleWarnings.size)
        assertEquals("unknown", parsed.ruleWarnings.single().ruleId)
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
        // condition. `certainty_is` occurs exactly once, because `goodRule` uses `visible_at_location`.
        val exported = SyncCodec.export(file)
        val mutated = exported.replaceFirst("\"certainty_is\"", "\"some_future_condition\"")
        assertTrue(mutated != exported, "test setup: the `certainty_is` discriminator was not found in the exported JSON")

        val parsed = SyncCodec.parseForImport(mutated)

        assertEquals(2, parsed.rules.size)
        val imported = parsed.rules.single { it.id == "future" }
        assertFalse(imported.enabled, "unrecognized condition -> rule imported disabled")
        assertEquals("Test rule", imported.name, "every other field should still decode")
        assertEquals(1, parsed.ruleWarnings.size)
        assertEquals("future", parsed.ruleWarnings.single().ruleId)
        assertEquals(setOf("future"), parsed.degradedRuleIds)

        val untouched = parsed.rules.single { it.id == "good" }
        assertEquals(goodRule, untouched, "an unrelated rule must not be affected")
    }

    @Test
    fun ruleSkeletonStaysInSyncWithRuleFields() {
        // Guards against Rule gaining a field that RuleSkeleton (which duplicates Rule's shape by
        // hand so `condition` can be left undecoded) doesn't know about -- with `ignoreUnknownKeys
        // = true`, a drifted skeleton would silently drop that field for every degraded rule
        // instead of failing to compile.
        val original = defaultRules(now).first().copy(id = "r1")
        val file = SyncFile(
            exportedAt = now, appVersion = "9.9.9",
            locations = emptyList(), rules = listOf(original),
            settings = emptyMap(), firedNotificationIds = emptyList(),
        )
        val exported = SyncCodec.export(file)
        val mutated = exported.replaceFirst("\"eclipse_kind_in\"", "\"some_future_condition\"")
        assertTrue(mutated != exported, "test setup: expected discriminator not found")

        val parsed = SyncCodec.parseForImport(mutated)
        val degraded = parsed.rules.single()

        assertEquals(original.id, degraded.id)
        assertEquals(original.name, degraded.name)
        assertEquals(original.phenomena, degraded.phenomena)
        assertEquals(original.locationIds, degraded.locationIds)
        assertEquals(original.schedule, degraded.schedule)
        assertEquals(original.hidden, degraded.hidden)
        assertEquals(original.createdAt, degraded.createdAt)
        assertEquals(original.modifiedAt, degraded.modifiedAt)
        // Only these two are expected to differ for a degraded rule:
        assertFalse(degraded.enabled)
        assertTrue(degraded.condition is Cond.Not)
    }
}
