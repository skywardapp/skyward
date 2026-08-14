package dev.fritze.skyward.core.visibility

import dev.fritze.skyward.core.model.Certainty
import dev.fritze.skyward.core.model.CometElements
import dev.fritze.skyward.core.model.CometMagParams
import dev.fritze.skyward.core.model.CometPayload
import dev.fritze.skyward.core.model.GeoPoint
import dev.fritze.skyward.core.model.LocalDetails
import dev.fritze.skyward.core.model.Occurrence
import dev.fritze.skyward.core.model.Phenomenon
import dev.fritze.skyward.core.model.Quality
import dev.fritze.skyward.core.model.SavedLocation
import dev.fritze.skyward.core.model.TimeWindow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

class CometVisibilityModelTest {

    private val model = CometVisibilityModel()

    // 2P/Encke elements, Horizons-validated (see KeplerTest.kt).
    private val enckeElements = CometElements(
        epoch = Instant.parse("2023-10-22T03:35:18.402Z"),
        eccentricity = 0.8477496967533629,
        perihelionDistanceAu = 0.3379482792219925,
        inclinationDeg = 11.41227811179314,
        ascendingNodeDeg = 334.1935846036774,
        argPerihelionDeg = 187.1342463695676,
        tpPerihelion = Instant.parse("2023-10-22T03:35:18.402Z"),
    )

    private fun loc(point: GeoPoint) = SavedLocation(
        id = "loc", name = "Test", point = point, isPrimary = true,
        createdAt = Instant.parse("2026-01-01T00:00:00Z"), modifiedAt = Instant.parse("2026-01-01T00:00:00Z"),
    )

    private fun cometOccurrence(magParams: CometMagParams, peakTime: Instant, window: TimeWindow) = Occurrence(
        id = "cm:encke:test",
        phenomenon = Phenomenon.COMET,
        sourceId = "jpl",
        title = "2P/Encke",
        window = window,
        peakTime = peakTime,
        certainty = Certainty.FORECAST,
        payload = CometPayload(
            designation = "2P/Encke",
            name = "Encke",
            elements = enckeElements,
            magParams = magParams,
            perihelionDate = enckeElements.tpPerihelion,
            peakMag = magParams.m1,
            peakMagDate = peakTime,
            magAtIngest = magParams.m1,
        ),
        fetchedAt = window.start,
        expiresAt = null,
    )

    @Test
    fun travelFieldsAreAlwaysNull() {
        val peak = enckeElements.tpPerihelion
        val occ = cometOccurrence(CometMagParams(m1 = 15.6, k1 = 4.5), peak, TimeWindow(peak - 1.days, peak + 1.days))
        val ctx = VisibilityContext(now = peak, ovationGrid = null)
        val result = model.evaluate(occ, loc(GeoPoint(52.0, 7.6)), ctx)

        assertNull(result.nearestVisiblePoint)
        assertNull(result.travelDistanceKm)
        assertNull(result.travelBearingDeg)
        assertNull(result.qualityAtNearestPoint)
    }

    @Test
    fun aHopelesslyFaintCometFailsTheMagnitudeGateRegardlessOfAltitude() {
        // M1=25 keeps the predicted magnitude far fainter than the 6.0 gate
        // at any plausible geometry, isolating the magnitude-gate behavior.
        val peak = enckeElements.tpPerihelion
        val occ = cometOccurrence(CometMagParams(m1 = 25.0, k1 = 4.5), peak, TimeWindow(peak - 1.days, peak + 1.days))
        val ctx = VisibilityContext(now = peak, ovationGrid = null)
        val result = model.evaluate(occ, loc(GeoPoint(52.0, 7.6)), ctx)

        assertEquals(Quality.NONE, result.quality)
        assertTrue(!result.visibleAtLocation)
        val details = result.localDetails as? LocalDetails.CometLocal
        assertNotNull(details)
        assertTrue(details.predictedMag > 6.0)
    }

    @Test
    fun qualityNeverExceedsMarginalWhenEitherGateFails() {
        // Property check across a spread of dates/locations: whenever the
        // model reports better than MARGINAL, both the magnitude and
        // altitude gates must actually have passed for that occurrence.
        val magParams = CometMagParams(m1 = 8.0, k1 = 4.5) // often but not always <= 6.0 near perihelion
        val peak = enckeElements.tpPerihelion
        val occ = cometOccurrence(magParams, peak, TimeWindow(peak - 30.days, peak + 30.days))

        for (dayOffset in listOf(-20, -10, -3, 0, 3, 10, 20)) {
            for (lat in listOf(-60.0, -20.0, 0.0, 20.0, 52.0, 60.0)) {
                val now = peak + dayOffset.days
                val ctx = VisibilityContext(now = now, ovationGrid = null)
                val result = model.evaluate(occ, loc(GeoPoint(lat, 7.6)), ctx)
                val details = result.localDetails as? LocalDetails.CometLocal
                assertNotNull(details, "day=$dayOffset lat=$lat")

                if (result.quality > Quality.NONE) {
                    assertTrue(details.predictedMag <= 6.0, "day=$dayOffset lat=$lat: quality=${result.quality} but mag=${details.predictedMag}")
                    assertTrue(details.maxAltDeg >= 15.0, "day=$dayOffset lat=$lat: quality=${result.quality} but maxAlt=${details.maxAltDeg}")
                }
                if (result.quality == Quality.NONE) {
                    assertNull(result.nearestVisiblePoint)
                }
            }
        }
    }

    @Test
    fun aPermanentDaylightWindowGetsNoneWithNullBestViewing() {
        // Svalbard midsummer: no astronomical night exists at all, so
        // regardless of the comet's brightness, it can never be observed dark.
        val peak = Instant.parse("2026-06-21T12:00:00Z")
        val occ = cometOccurrence(CometMagParams(m1 = 2.0, k1 = 4.5), peak, TimeWindow(peak - 1.days, peak + 1.days))
        val ctx = VisibilityContext(now = peak, ovationGrid = null)
        val result = model.evaluate(occ, loc(GeoPoint(78.2, 15.6)), ctx)

        assertEquals(Quality.NONE, result.quality)
        val details = result.localDetails as? LocalDetails.CometLocal
        assertNotNull(details)
        assertNull(details.bestViewingStart)
        assertNull(details.bestViewingEnd)
    }
}
