package dev.fritze.skyward.core.sources

import dev.fritze.skyward.core.astro.toAstroTime
import dev.fritze.skyward.core.model.ConjunctionPayload
import dev.fritze.skyward.core.model.TimeWindow
import io.github.cosinekitty.astronomy.Body
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * §7.6. `geocentricSeparationDeg` is checked against apparent RA/Dec pulled
 * live from JPL Horizons (ssd.jpl.nasa.gov/api, QUANTITIES=2, geocentric);
 * the end-to-end refresh() is checked against the well-documented Venus-
 * Jupiter conjunction of 2023-03-02, whose minimum separation and timing
 * were independently derived here from a dense (6h-step) Horizons sweep —
 * not looked up from a press "closest conjunction" summary, since those
 * round differently across sources.
 */
class ConjunctionSourceTest {

    private val source = ConjunctionSource()

    private suspend fun refresh(start: Instant, end: Instant) = source.refresh(
        RefreshRequest(
            now = start,
            horizon = TimeWindow(start, end),
            locations = emptyList(),
            state = emptyMap(),
            settings = SourceSettings(),
            derivedThresholds = DerivedThresholds(null, null, null),
        )
    )

    @Test
    fun geocentricSeparationMatchesHorizonsApparentPositions() {
        // Venus/Jupiter apparent geocentric RA/Dec at 2023-03-01T00:00 UTC,
        // fetched live 2026-08-13 (CENTER=500@399, QUANTITIES=2). Independently
        // computed separation via the spherical law of cosines: 1.306097 deg.
        val t = Instant.parse("2023-03-01T00:00:00Z").toAstroTime()
        val sep = geocentricSeparationDeg(Body.Venus, Body.Jupiter, t)
        assertEquals(1.306097, sep, 0.001)
    }

    @Test
    fun findsTheDocumentedMarch2023VenusJupiterConjunction() = runTest {
        val result = refresh(Instant.parse("2023-02-20T00:00:00Z"), Instant.parse("2023-03-10T00:00:00Z"))

        val conjunction = result.occurrences.firstOrNull { occ ->
            val payload = occ.payload as ConjunctionPayload
            setOf(payload.body1, payload.body2) == setOf("Venus", "Jupiter")
        }
        assertNotNull(conjunction, "expected a Venus-Jupiter conjunction occurrence in this window")

        val payload = conjunction.payload as ConjunctionPayload
        val expectedClosest = Instant.parse("2023-03-02T06:00:00Z")
        val delta = (payload.timeOfClosest - expectedClosest).let { if (it.isNegative()) -it else it }
        assertTrue(delta < 90.minutes, "expected closest approach near $expectedClosest, got ${payload.timeOfClosest}")
        assertEquals(0.4906, payload.minSeparationDeg, 0.02)
        assertEquals(payload.timeOfClosest, conjunction.peakTime)
        assertEquals(
            TimeWindow(payload.timeOfClosest - 12.hours, payload.timeOfClosest + 12.hours),
            conjunction.window,
        )
        assertEquals("cj:jupiter-venus:${payload.timeOfClosest.toYearMonthDayKey()}", conjunction.id)
    }

    @Test
    fun everyEmittedConjunctionIsBelowItsOwnThresholdAndObservable() = runTest {
        val result = refresh(Instant.parse("2024-01-01T00:00:00Z"), Instant.parse("2025-01-01T00:00:00Z"))
        assertTrue(result.occurrences.isNotEmpty(), "expected at least one conjunction across a full year")

        for (occ in result.occurrences) {
            val payload = occ.payload as ConjunctionPayload
            val threshold = if ("Moon" in setOf(payload.body1, payload.body2)) 2.0 else 1.0
            assertTrue(payload.minSeparationDeg < threshold, "${payload.body1}-${payload.body2}: ${payload.minSeparationDeg} should be < $threshold")

            val t = payload.timeOfClosest.toAstroTime()
            val bodyA = Body.valueOf(payload.body1)
            val bodyB = Body.valueOf(payload.body2)
            assertTrue(io.github.cosinekitty.astronomy.angleFromSun(bodyA, t) > 15.0, "${payload.body1} should be >15deg from Sun")
            assertTrue(io.github.cosinekitty.astronomy.angleFromSun(bodyB, t) > 15.0, "${payload.body2} should be >15deg from Sun")
        }
    }

    @Test
    fun idsAreUniqueAcrossAFullYear() = runTest {
        val result = refresh(Instant.parse("2024-01-01T00:00:00Z"), Instant.parse("2025-01-01T00:00:00Z"))
        val ids = result.occurrences.map { it.id }
        assertEquals(ids.toSet().size, ids.size, "expected all conjunction ids to be unique, got $ids")
    }
}
