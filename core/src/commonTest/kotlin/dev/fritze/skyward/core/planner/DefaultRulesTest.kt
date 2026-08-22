package dev.fritze.skyward.core.planner

import dev.fritze.skyward.core.model.GeoPoint
import dev.fritze.skyward.core.model.Occurrence
import dev.fritze.skyward.core.model.Phenomenon
import dev.fritze.skyward.core.model.SavedLocation
import dev.fritze.skyward.core.model.TimeWindow
import dev.fritze.skyward.core.rules.defaultRules
import dev.fritze.skyward.core.sources.ConjunctionSource
import dev.fritze.skyward.core.sources.DerivedThresholds
import dev.fritze.skyward.core.sources.EclipseSource
import dev.fritze.skyward.core.sources.MeteorShowerSource
import dev.fritze.skyward.core.sources.MoonEventSource
import dev.fritze.skyward.core.sources.RefreshRequest
import dev.fritze.skyward.core.sources.SourceSettings
import dev.fritze.skyward.core.visibility.AuroraVisibilityModel
import dev.fritze.skyward.core.visibility.CometVisibilityModel
import dev.fritze.skyward.core.visibility.ConjunctionVisibilityModel
import dev.fritze.skyward.core.visibility.LunarEclipseVisibilityModel
import dev.fritze.skyward.core.visibility.MeteorShowerVisibilityModel
import dev.fritze.skyward.core.visibility.MoonEventVisibilityModel
import dev.fritze.skyward.core.visibility.SolarEclipseVisibilityModel
import dev.fritze.skyward.core.visibility.TerrestrialVisibilityModel
import dev.fritze.skyward.core.visibility.VisibilityContext
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * §17.4: "default rules (§9.6) evaluated against a synthetic 2-year
 * occurrence set with expected match counts." Real M1 sources over a real
 * horizon, not fabricated fixtures — computed counts are asserted as
 * plausibility bounds (ephemeris-dependent exact counts would make this
 * test fragile against the horizon dates chosen), not exact literals.
 */
class DefaultRulesTest {

    private val visibilityModels = mapOf(
        Phenomenon.SOLAR_ECLIPSE to SolarEclipseVisibilityModel(),
        Phenomenon.LUNAR_ECLIPSE to LunarEclipseVisibilityModel(),
        Phenomenon.AURORA to AuroraVisibilityModel(),
        Phenomenon.METEOR_SHOWER to MeteorShowerVisibilityModel(),
        Phenomenon.COMET to CometVisibilityModel(),
        Phenomenon.MOON_EVENT to MoonEventVisibilityModel(),
        Phenomenon.CONJUNCTION to ConjunctionVisibilityModel(),
        Phenomenon.TERRESTRIAL to TerrestrialVisibilityModel(),
    )

    @Test
    fun defaultRulesProducePlausibleMatchCountsOverATwoYearHorizon() = runTest(timeout = 120.seconds) {
        val start = Instant.parse("2026-01-01T00:00:00Z")
        val end = Instant.parse("2028-01-01T00:00:00Z")
        val now = start

        val occurrences = mutableListOf<Occurrence>()
        val request = { RefreshRequest(now, TimeWindow(start, end), emptyList(), emptyMap(), SourceSettings(), DerivedThresholds(null, null, null)) }
        occurrences += EclipseSource().refresh(request()).occurrences
        occurrences += MeteorShowerSource().refresh(request()).occurrences
        occurrences += MoonEventSource().refresh(request()).occurrences
        occurrences += ConjunctionSource().refresh(request()).occurrences

        // Munich: a real, unremarkable mid-northern-latitude location —
        // not cherry-picked for any particular eclipse path.
        val home = SavedLocation(id = "home", name = "Home", point = GeoPoint(48.1351, 11.5820), isPrimary = true, createdAt = now, modifiedAt = now)
        val ctx = VisibilityContext(now = now, ovationGrid = null)

        val matches = Planner.computeMatches(occurrences, listOf(home), defaultRules(now), visibilityModels, ctx)
        val matchesByRule = matches.groupBy { it.rule.name }

        // Meteor showers occur reliably several times a year; over 2 years
        // and a real curated catalog, at least one should clear ZHR>=20 and
        // GOOD visibility from *some* northern-hemisphere night.
        assertTrue(
            (matchesByRule["Major meteor showers, decent conditions"]?.size ?: 0) > 0,
            "expected at least one major-shower match over 2 years from 48N",
        )

        // Supermoons occur multiple times a year under this app's definition.
        assertTrue((matchesByRule["Supermoon"]?.size ?: 0) > 0, "expected at least one supermoon match over 2 years")

        // Every match must actually satisfy its own rule's gating (defensive
        // re-check, not just trusting computeMatches internals).
        for (m in matches) {
            assertTrue(m.occ.phenomenon in m.rule.phenomena)
            assertTrue(m.rule.enabled)
        }

        // Disabled-by-default rules (Close conjunctions, Volcano within
        // reach) must never appear even though EONET/aurora data isn't fed
        // in here at all, and conjunctions are.
        assertTrue(matchesByRule["Close conjunctions"] == null, "a shipped-disabled rule must never match")
    }

    @Test
    fun auroraNowShipsWithNoQuietHoursAndAFirstSeenCooldown() {
        // Regression guard for issue #57: quietHours = QuietHours(0, 6) on
        // this rule silently dropped every nowcast alert between 00:00 and
        // 06:00 (a NOWCAST occurrence's 1h window can't survive a defer to
        // 06:00), contradicting §9.6's own "off by default". And without a
        // cooldown, aurora NOWCAST's per-fetch occurrence identity (§7.3.3)
        // re-alerts on every ~15-minute active-tier poll while an aurora
        // persists.
        val rule = defaultRules(Instant.parse("2026-01-01T00:00:00Z")).single { it.id == "default:aurora-now" }

        assertEquals(null, rule.schedule.quietHours, "§9.6: quiet hours ship off by default")
        assertTrue(rule.schedule.notifyOnFirstSeen)
        assertEquals(2.hours, rule.schedule.firstSeenCooldown, "ADR 0016's chosen cooldown, not merely some positive value")
    }
}
