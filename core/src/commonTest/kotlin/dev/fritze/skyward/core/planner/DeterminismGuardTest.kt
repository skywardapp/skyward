package dev.fritze.skyward.core.planner

import dev.fritze.skyward.core.model.GeoPoint
import dev.fritze.skyward.core.model.Occurrence
import dev.fritze.skyward.core.model.Phenomenon
import dev.fritze.skyward.core.model.PlannedNotification
import dev.fritze.skyward.core.model.SavedLocation
import dev.fritze.skyward.core.model.TimeWindow
import dev.fritze.skyward.core.rules.defaultRules
import dev.fritze.skyward.core.sources.ConjunctionSource
import dev.fritze.skyward.core.sources.DerivedThresholds
import dev.fritze.skyward.core.sources.EclipseSource
import dev.fritze.skyward.core.sources.EventSource
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
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * §17.6: runs the full pipeline (sources -> visibility -> rules -> planner)
 * twice against the same real-source horizon and asserts byte-identical
 * planned-notification sets, protecting the natural-key/dedup design —
 * nothing in the pipeline may depend on wall-clock time, iteration order,
 * hash-based collection ordering, or any other source of run-to-run drift.
 */
class DeterminismGuardTest {

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

    private suspend fun runFullPipeline(now: Instant, horizonEnd: Instant, location: SavedLocation): List<PlannedNotification> {
        val horizon = TimeWindow(now, horizonEnd)
        val computedSources: List<EventSource> = listOf(EclipseSource(), MeteorShowerSource(), MoonEventSource(), ConjunctionSource())
        val occurrences: List<Occurrence> = computedSources.flatMap { source ->
            source.refresh(
                RefreshRequest(
                    now = now,
                    horizon = horizon,
                    locations = listOf(location),
                    state = emptyMap(),
                    settings = SourceSettings(),
                    derivedThresholds = DerivedThresholds(null, null, null),
                ),
            ).occurrences
        }

        val rules = defaultRules(now)
        val ctx = VisibilityContext(now = now, ovationGrid = null)
        val matches = Planner.computeMatches(occurrences, listOf(location), rules, visibilityModels, ctx)
        return Planner.desiredNotifications(matches, now, TimeZone.UTC)
    }

    @Test
    fun theFullPipelineIsByteIdenticalAcrossTwoRuns() = runTest(timeout = 120.seconds) {
        val now = Instant.parse("2026-01-01T00:00:00Z")
        val horizonEnd = Instant.parse("2028-01-01T00:00:00Z")
        val location = SavedLocation(
            id = "home", name = "Home", point = GeoPoint(48.1351, 11.5820),
            isPrimary = true, createdAt = now, modifiedAt = now,
        )

        val firstRun = runFullPipeline(now, horizonEnd, location)
        val secondRun = runFullPipeline(now, horizonEnd, location)

        assertTrue(firstRun.isNotEmpty(), "expected at least one planned notification to make this a meaningful check")
        assertEquals(firstRun, secondRun, "the full pipeline must be deterministic run-to-run")

        // Byte-identical ids specifically, since that's the natural-key/dedup
        // property this guard exists to protect (§17.6's own framing).
        assertEquals(firstRun.map { it.id }, secondRun.map { it.id })
        assertEquals(firstRun.map { it.fireAt }, secondRun.map { it.fireAt })
    }
}
