package dev.fritze.skyward.core.visibility

import dev.fritze.skyward.core.astro.toAstroTime
import dev.fritze.skyward.core.astro.toInstant
import dev.fritze.skyward.core.model.GeoPoint
import dev.fritze.skyward.core.model.Occurrence
import dev.fritze.skyward.core.model.Quality
import dev.fritze.skyward.core.model.SavedLocation
import dev.fritze.skyward.core.model.SolarEclipseKind
import dev.fritze.skyward.core.model.SolarEclipsePayload
import dev.fritze.skyward.core.model.TimeWindow
import dev.fritze.skyward.core.sources.DerivedThresholds
import dev.fritze.skyward.core.sources.EclipseSource
import dev.fritze.skyward.core.sources.RefreshRequest
import dev.fritze.skyward.core.sources.SourceSettings
import io.github.cosinekitty.astronomy.Observer
import io.github.cosinekitty.astronomy.searchLocalSolarEclipse
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class SolarEclipseVisibilityModelTest {

    private val model = SolarEclipseVisibilityModel()
    private val source = EclipseSource()
    private val ctx = VisibilityContext(now = Instant.parse("2026-08-01T00:00:00Z"), ovationGrid = null)

    private fun loc(point: GeoPoint) = SavedLocation(
        id = "loc",
        name = "Test",
        point = point,
        isPrimary = true,
        createdAt = ctx.now,
        modifiedAt = ctx.now,
    )

    private suspend fun august2026TotalEclipse(): Occurrence {
        val result = source.refresh(
            RefreshRequest(
                now = Instant.parse("2026-08-01T00:00:00Z"),
                horizon = TimeWindow(Instant.parse("2026-08-01T00:00:00Z"), Instant.parse("2026-08-20T00:00:00Z")),
                locations = emptyList(),
                state = emptyMap(),
                settings = SourceSettings(),
                derivedThresholds = DerivedThresholds(null, null, null),
            ),
        )
        return result.occurrences.first { it.id == "se:20260812" }
    }

    @Test
    fun aLocationOnTheCentralPathSeesExcellentTotality() = runTest {
        val occ = august2026TotalEclipse()
        val payload = occ.payload as SolarEclipsePayload
        val onPath = payload.centralPath[payload.centralPath.size / 2].point

        val result = model.evaluate(occ, loc(onPath), ctx)

        assertTrue(result.visibleAtLocation)
        assertEquals(Quality.EXCELLENT, result.quality)
        assertNull(result.travelDistanceKm, "expected no travel guidance when already visible")
        val details = result.localDetails as? dev.fritze.skyward.core.model.LocalDetails.SolarEclipseLocal
        assertNotNull(details)
        assertEquals(SolarEclipseKind.TOTAL, details.localKind)
    }

    @Test
    fun aLocationTwoHundredKmFromThePathGetsTravelGuidanceWithinTenPercent() = runTest {
        val occ = august2026TotalEclipse()
        val payload = occ.payload as SolarEclipsePayload
        // A short-central-duration sample sits near the grazing (sunrise/
        // sunset-limited) end of the path, where the umbral path is
        // narrowest — 200km off *there* is reliably outside totality,
        // unlike near mid-path where this eclipse's path is wide enough
        // that 200km can still land inside it.
        val idx = payload.centralPath.indices.minBy { payload.centralPath[it].centralDurationSec ?: Double.MAX_VALUE }
        val sample = payload.centralPath[idx]

        // Offset perpendicular to the path's local direction so the nearest
        // path point stays close to `sample` rather than sliding along the path.
        val neighborIdx = if (idx + 1 < payload.centralPath.size) idx + 1 else idx - 1
        val neighbor = payload.centralPath[neighborIdx]
        val pathBearing = initialBearingDeg(sample.point, neighbor.point)
        val perpendicular = (pathBearing + 90.0) % 360.0
        val offLocation = destinationPoint(sample.point, 200.0, perpendicular)

        val result = model.evaluate(occ, loc(offLocation), ctx)

        val travelDistanceKm = result.travelDistanceKm
        assertNotNull(travelDistanceKm, "expected travel guidance off the central path, got quality=${result.quality}")
        val ratio = travelDistanceKm / 200.0
        assertTrue(ratio in 0.9..1.1, "expected ~200km, got $travelDistanceKm")
        assertEquals(Quality.EXCELLENT, result.qualityAtNearestPoint)
    }

    @Test
    fun sunDownAtLocalPeakIsNoneWithNoLocalDetailsClaimOfVisibility() = runTest {
        val occ = august2026TotalEclipse()
        // Antipodal-ish point to the path: night side of Earth at the eclipse's UTC peak.
        val nightSide = GeoPoint(0.0, 20.0) // roughly opposite the Atlantic/European path longitude band
        val result = model.evaluate(occ, loc(nightSide), ctx)
        // Either genuinely below horizon (NONE) or a valid low-obscuration
        // partial — either way it must not claim EXCELLENT/visible totality.
        assertTrue(result.quality != Quality.EXCELLENT)
    }

    @Test
    fun partialOnlyTravelGuidanceNeverPointsAtADifferentEclipse() = runTest {
        // Reuse the fast, narrow-horizon August 2026 total-eclipse fixture
        // rather than searching a wide horizon for a genuinely partial-only
        // eclipse (rare enough within a couple of years that it forces a
        // slow multi-year EclipseSource query -- see the other tests'
        // comments on refresh() path-sampling every total/annular eclipse
        // in the horizon). Clearing centralPath forces the same
        // partial-only code path (nearestObscurationAtLeast) this fix
        // targets, against this real eclipse's real geometry --
        // `searchLocalSolarEclipse` inside the model only cares about the
        // occurrence's window/observer, not the payload's centralPath.
        val totalEclipse = august2026TotalEclipse()
        val payload = totalEclipse.payload as SolarEclipsePayload
        val partialOnly = totalEclipse.copy(payload = payload.copy(centralPath = emptyList()))

        // Antipodal-ish to the point of greatest eclipse, to land somewhere
        // this eclipse likely isn't locally visible at all -- exercising the
        // travel-guidance search path.
        val farLoc = loc(GeoPoint(-payload.greatestEclipsePoint.latDeg, payload.greatestEclipsePoint.lonDeg + 150.0))
        val visResult = model.evaluate(partialOnly, farLoc, ctx)

        val target = assertNotNull(visResult.nearestVisiblePoint, "expected partial-only travel guidance")
        // Whatever the search found, it must describe *this* eclipse's own
        // local circumstances -- re-searching at the target must land a peak
        // within this occurrence's own window, not some other, later
        // eclipse that `searchLocalSolarEclipse` returns for a probe point
        // where this eclipse isn't visible at all.
        val searchStart = (partialOnly.window.start - 1.hours).toAstroTime()
        val localAtTarget = searchLocalSolarEclipse(searchStart, Observer(target.latDeg, target.lonDeg, 0.0))
        assertTrue(
            localAtTarget.peak.time.toInstant() in partialOnly.window.start..partialOnly.window.end,
            "expected travel guidance to describe occurrence ${partialOnly.id}'s own eclipse",
        )
    }

    @Test
    fun aDeepPartialWithTheSunAlreadyDownTravelsTowardDaylightRatherThanZeroKm() = runTest {
        // Clearing centralPath forces the partial-only travel search
        // (nearestObscurationAtLeast) against this real eclipse's real
        // geometry -- see the previous test for why the total-eclipse
        // fixture is reused rather than hunting a genuinely partial-only
        // eclipse.
        val totalEclipse = august2026TotalEclipse()
        val payload = totalEclipse.payload as SolarEclipsePayload
        val partialOnly = totalEclipse.copy(payload = payload.copy(centralPath = emptyList()))

        // Southern Italy on 2026-08-12: the sun sets *during* the eclipse,
        // so obscuration climbs past 0.8 while the sun is already below the
        // horizon at local peak. §8.2 rates that NONE ("sun down"), which
        // sends the travel search running from a location that obscuration
        // alone would have called GOOD -- the case the bisection's lower
        // bound used to assume away.
        val terminatorAdjacent = GeoPoint(40.0, 15.0)
        val searchStart = (partialOnly.window.start - 1.hours).toAstroTime()
        val here = searchLocalSolarEclipse(searchStart, Observer(terminatorAdjacent.latDeg, terminatorAdjacent.lonDeg, 0.0))
        assertTrue(here.peak.time.toInstant() in partialOnly.window.start..partialOnly.window.end)
        assertTrue(here.obscuration >= 0.8, "fixture precondition: deep partial locally, got ${here.obscuration}")
        assertTrue(here.peak.altitude <= 0.0, "fixture precondition: sun down at peak, got ${here.peak.altitude}")

        val result = model.evaluate(partialOnly, loc(terminatorAdjacent), ctx)

        assertEquals(Quality.NONE, result.quality)
        val travelDistanceKm = assertNotNull(result.travelDistanceKm, "expected partial-only travel guidance")
        assertTrue(
            travelDistanceKm > 100.0,
            "the daylit side is hundreds of km away; got ${travelDistanceKm}km, i.e. 'travel nowhere for an eclipse that happens at night'",
        )
        val target = assertNotNull(result.nearestVisiblePoint)
        val there = searchLocalSolarEclipse(searchStart, Observer(target.latDeg, target.lonDeg, 0.0))
        assertTrue(there.peak.time.toInstant() in partialOnly.window.start..partialOnly.window.end)
        assertTrue(there.obscuration >= 0.8, "travel target must reach the 80% threshold, got ${there.obscuration}")
        assertTrue(there.peak.altitude > 0.0, "travel target must have the sun up at peak, got ${there.peak.altitude}")
        assertEquals(Quality.GOOD, result.qualityAtNearestPoint)
    }

    @Test
    fun farFromTheEclipseEntirelyGetsNoVisibilityAndStillOffersTravelGuidance() = runTest {
        val occ = august2026TotalEclipse()
        // New Zealand: nowhere near the August 2026 path (Siberia/Arctic/Greenland/Iceland/Spain).
        val farAway = loc(GeoPoint(-41.0, 174.7))
        val result = model.evaluate(occ, farAway, ctx)
        assertTrue(!result.visibleAtLocation)
        assertNotNull(result.nearestVisiblePoint)
        val travelDistanceKm = result.travelDistanceKm
        assertNotNull(travelDistanceKm)
        assertTrue(travelDistanceKm > 1000.0, "expected NZ to be far from a path through the northern hemisphere")
    }
}
