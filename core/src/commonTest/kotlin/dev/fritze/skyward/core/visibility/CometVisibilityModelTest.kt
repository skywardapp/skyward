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

    /**
     * A synthetic comet, not a real one: `q = 3 au` on a parabolic orbit
     * keeps it far enough away that its geometry barely moves over a
     * lunation, and an implausibly bright `m1` keeps it well inside the
     * magnitude gate throughout. Both are deliberate — they hold every axis
     * of §17.4's "magnitude/altitude/moon matrix" still except the one under
     * test, which no real comet's fast-changing geometry would do.
     */
    private val slowBrightElements = CometElements(
        epoch = Instant.parse("2027-03-01T00:00:00Z"),
        eccentricity = 1.0,
        perihelionDistanceAu = 3.0,
        inclinationDeg = 20.0,
        ascendingNodeDeg = 100.0,
        argPerihelionDeg = 60.0,
        tpPerihelion = Instant.parse("2027-03-01T00:00:00Z"),
    )

    /**
     * Same trick, tilted into a polar orbit with the argument of perihelion
     * putting the comet over the *south* ecliptic pole: geocentric
     * declination stays between -80 deg and -66 deg, which from 52 deg N
     * never clears the horizon at all.
     */
    private val farSouthernElements = slowBrightElements.copy(
        inclinationDeg = 90.0,
        ascendingNodeDeg = 0.0,
        argPerihelionDeg = 270.0,
    )

    private fun syntheticOccurrence(elements: CometElements, magParams: CometMagParams) = Occurrence(
        id = "cm:synthetic:test",
        phenomenon = Phenomenon.COMET,
        sourceId = "jpl",
        title = "Synthetic comet",
        window = TimeWindow(elements.tpPerihelion - 120.days, elements.tpPerihelion + 120.days),
        // Peak deliberately in the past relative to every `now` below, so the
        // model evaluates "how does this look tonight" at `now` rather than
        // jumping to a shared peak date and making all these cases identical.
        peakTime = elements.tpPerihelion - 400.days,
        certainty = Certainty.FORECAST,
        payload = CometPayload(
            designation = "X/2027 T1",
            name = "Synthetic",
            elements = elements,
            magParams = magParams,
            perihelionDate = elements.tpPerihelion,
            peakMag = magParams.m1,
            peakMagDate = elements.tpPerihelion,
            magAtIngest = magParams.m1,
        ),
        fetchedAt = elements.tpPerihelion - 120.days,
        expiresAt = null,
    )

    @Test
    fun aFarSouthernCometFrom52NorthIsNoneForAReasonTheDetailsSpellOut() {
        // §17.4: "a far-southern-declination comet evaluated from 52 deg N
        // returns NONE with a stated reason and null travel fields".
        val occ = syntheticOccurrence(farSouthernElements, CometMagParams(m1 = -4.0, k1 = 4.0))

        for (dayOffset in listOf(-30, -10, 0, 10, 30, 60)) {
            val now = farSouthernElements.tpPerihelion + dayOffset.days
            val result = model.evaluate(occ, loc(GeoPoint(52.0, 7.6)), VisibilityContext(now = now, ovationGrid = null))
            val details = assertNotNull(result.localDetails as? LocalDetails.CometLocal, "day $dayOffset")

            assertEquals(Quality.NONE, result.quality, "day $dayOffset")
            assertTrue(!result.visibleAtLocation, "day $dayOffset")

            // The reason has to be *readable*, not merely correct: a bare
            // NONE would leave the UI (13.3) and the notification copy
            // (10.5) with nothing to say. These are the fields they render.
            assertTrue(
                details.predictedMag <= 6.0,
                "day $dayOffset: this case must fail on altitude, not brightness — mag was ${details.predictedMag}",
            )
            assertTrue(
                details.maxAltDeg < 0.0,
                "day $dayOffset: expected a comet that never rises, got maxAlt ${details.maxAltDeg}",
            )
            // A night exists here (unlike the permanent-daylight case below),
            // so the model can say *when* it looked — it just never found the
            // comet above the horizon during it.
            assertNotNull(details.maxAltTime, "day $dayOffset")
            assertNull(details.bestViewingStart, "day $dayOffset")
            assertNull(details.bestViewingEnd, "day $dayOffset")

            // 8.6: comets are a timing/latitude matter, never a travel one.
            assertNull(result.nearestVisiblePoint, "day $dayOffset")
            assertNull(result.travelDistanceKm, "day $dayOffset")
            assertNull(result.travelBearingDeg, "day $dayOffset")
            assertNull(result.qualityAtNearestPoint, "day $dayOffset")
        }

        // And the same comet from the southern hemisphere is a fine sight —
        // otherwise this test would also pass against a model that returns
        // NONE for everything.
        val fromDunedin = model.evaluate(
            occ,
            loc(GeoPoint(-45.87, 170.50)),
            VisibilityContext(now = farSouthernElements.tpPerihelion, ovationGrid = null),
        )
        assertTrue(fromDunedin.visibleAtLocation, "the same comet should be visible from 46 deg S")
    }

    @Test
    fun aBrightMoonSharingTheCometsSkyCostsOneQualityLevel() {
        // 8.6's moon term is the axis of the "magnitude/altitude/moon matrix"
        // nothing asserted. Isolating it needs the other two axes held still,
        // which is what `slowBrightElements` is for: across all six nights
        // below the predicted magnitude stays near -0.5 and the maximum
        // altitude near 70 deg, so both gates and both magnitude/altitude
        // brackets are identical and the moon is the only thing that moves.
        //
        // Three consecutive lunations rather than one pair: a single
        // coincidence could line up, but a quality that drops on all three
        // full moons and recovers on all three new moons is the moon term.
        val occ = syntheticOccurrence(slowBrightElements, CometMagParams(m1 = -4.0, k1 = 4.0))
        val location = loc(GeoPoint(52.0, 7.6))
        val newMoonNights = listOf("2027-02-06", "2027-03-08", "2027-04-07")
        val fullMoonNights = listOf("2027-02-21", "2027-03-22", "2027-04-21")

        val samples = (newMoonNights + fullMoonNights).associateWith { date ->
            val now = Instant.parse("${date}T22:00:00Z")
            model.evaluate(occ, location, VisibilityContext(now = now, ovationGrid = null))
        }

        // The controls: same brackets everywhere, so any quality difference
        // below cannot be coming from magnitude or altitude.
        for ((date, result) in samples) {
            val details = assertNotNull(result.localDetails as? LocalDetails.CometLocal, date)
            assertTrue(details.predictedMag <= 2.0, "$date: magnitude bracket moved — mag ${details.predictedMag}")
            assertTrue(details.maxAltDeg >= 25.0, "$date: altitude bracket moved — maxAlt ${details.maxAltDeg}")
        }

        for (date in newMoonNights) {
            assertEquals(Quality.EXCELLENT, samples.getValue(date).quality, "$date is a dark night for this comet")
        }
        for (date in fullMoonNights) {
            assertEquals(Quality.GOOD, samples.getValue(date).quality, "$date has a full moon in the comet's sky")
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
