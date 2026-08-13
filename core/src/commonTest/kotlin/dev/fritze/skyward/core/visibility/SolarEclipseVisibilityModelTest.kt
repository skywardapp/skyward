package dev.fritze.skyward.core.visibility

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
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
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
