package dev.fritze.skyward.core.visibility

import dev.fritze.skyward.core.astro.subPoint
import dev.fritze.skyward.core.astro.toAstroTime
import dev.fritze.skyward.core.model.GeoPoint
import dev.fritze.skyward.core.model.LocalDetails
import dev.fritze.skyward.core.model.LunarEclipsePayload
import dev.fritze.skyward.core.model.Occurrence
import dev.fritze.skyward.core.model.Quality
import dev.fritze.skyward.core.model.SavedLocation
import dev.fritze.skyward.core.model.TimeWindow
import dev.fritze.skyward.core.sources.DerivedThresholds
import dev.fritze.skyward.core.sources.EclipseSource
import dev.fritze.skyward.core.sources.RefreshRequest
import dev.fritze.skyward.core.sources.SourceSettings
import io.github.cosinekitty.astronomy.Body
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class LunarEclipseVisibilityModelTest {

    private val model = LunarEclipseVisibilityModel()
    private val source = EclipseSource()
    private val ctx = VisibilityContext(now = Instant.parse("2026-01-01T00:00:00Z"), ovationGrid = null)

    private fun loc(point: GeoPoint) = SavedLocation(
        id = "loc",
        name = "Test",
        point = point,
        isPrimary = true,
        createdAt = ctx.now,
        modifiedAt = ctx.now,
    )

    // Shared across all tests below: EclipseSource.refresh() also full-path-
    // samples every solar total/annular eclipse in the horizon, so calling
    // it once per test (as EclipseSourceTest itself learned, M1) is
    // needlessly slow. A 15-month horizon reliably contains 2+ lunar
    // eclipses (they occur roughly twice a year) while keeping the solar
    // path-sampling cost down.
    private var cachedLunarEclipses: List<Occurrence>? = null

    private suspend fun lunarEclipses(): List<Occurrence> {
        cachedLunarEclipses?.let { return it }
        val result = source.refresh(
            RefreshRequest(
                now = ctx.now,
                horizon = TimeWindow(Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2027-04-01T00:00:00Z")),
                locations = emptyList(),
                state = emptyMap(),
                settings = SourceSettings(),
                derivedThresholds = DerivedThresholds(null, null, null),
            ),
        )
        val lunar = result.occurrences.filter { it.id.startsWith("le:") }
        cachedLunarEclipses = lunar
        return lunar
    }

    @Test
    fun moonUpTheEntireUmbralPhaseIsExcellent() = runTest(timeout = 120.seconds) {
        val umbralEclipse = lunarEclipses().firstOrNull { (it.payload as LunarEclipsePayload).partialBegin != null }
        assertNotNull(umbralEclipse, "expected at least one TOTAL/PARTIAL lunar eclipse in this horizon")
        val payload = umbralEclipse.payload as LunarEclipsePayload
        val partialBegin = requireNotNull(payload.partialBegin)
        val partialEnd = requireNotNull(payload.partialEnd)

        // Sub-lunar point at mid-umbral-phase: Moon is at zenith there, so
        // it's up (well above the horizon) for the whole nearby window.
        val midUmbral = Instant.fromEpochMilliseconds((partialBegin.toEpochMilliseconds() + partialEnd.toEpochMilliseconds()) / 2)
        val subLunar = subPoint(Body.Moon, midUmbral.toAstroTime())

        val result = model.evaluate(umbralEclipse, loc(subLunar), ctx)

        assertTrue(result.visibleAtLocation)
        assertEquals(Quality.EXCELLENT, result.quality)
        assertNull(result.travelDistanceKm)
        val details = result.localDetails as? LocalDetails.LunarEclipseLocal
        assertNotNull(details)
        assertEquals(1.0, details.umbralFractionVisible, 0.0)
    }

    @Test
    fun moonBelowHorizonEntirelyGetsNoneAndTravelGuidanceTowardTheSubLunarPoint() = runTest(timeout = 120.seconds) {
        val occ = lunarEclipses().first()
        val payload = occ.payload as LunarEclipsePayload
        val mid = Instant.fromEpochMilliseconds(
            (payload.penumbralBegin.toEpochMilliseconds() + payload.penumbralEnd.toEpochMilliseconds()) / 2,
        )
        val subLunar = subPoint(Body.Moon, mid.toAstroTime())
        // The antipode of the sub-lunar point has the Moon at nadir (straight down) — guaranteed below the horizon.
        val antipode = GeoPoint(-subLunar.latDeg, ((subLunar.lonDeg + 180.0 + 540.0) % 360.0) - 180.0)

        val result = model.evaluate(occ, loc(antipode), ctx)

        assertTrue(!result.visibleAtLocation)
        assertEquals(Quality.NONE, result.quality)
        assertNotNull(result.nearestVisiblePoint)
        val travelDistanceKm = result.travelDistanceKm
        assertNotNull(travelDistanceKm)
        // Antipodal point -> travel guidance should point roughly toward the far side of Earth.
        assertTrue(travelDistanceKm > 5000.0, "expected a large distance from the antipode, got $travelDistanceKm")
    }

    @Test
    fun everyRealFixtureEvaluatesWithoutCrashing() = runTest(timeout = 120.seconds) {
        val lunarOccs = lunarEclipses()
        assertTrue(lunarOccs.isNotEmpty(), "expected at least one lunar eclipse in this horizon")
        val here = loc(GeoPoint(52.0, 7.6))
        for (occ in lunarOccs) {
            val result = model.evaluate(occ, here, ctx)
            assertTrue(result.quality in Quality.entries)
        }
    }
}
