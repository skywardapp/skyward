package dev.fritze.skyward.core.visibility

import dev.fritze.skyward.core.model.AuroraForecastKind
import dev.fritze.skyward.core.model.AuroraPayload
import dev.fritze.skyward.core.model.Certainty
import dev.fritze.skyward.core.model.GeoPoint
import dev.fritze.skyward.core.model.LocalDetails
import dev.fritze.skyward.core.model.Occurrence
import dev.fritze.skyward.core.model.Phenomenon
import dev.fritze.skyward.core.model.Quality
import dev.fritze.skyward.core.model.SavedLocation
import dev.fritze.skyward.core.model.TimeWindow
import dev.fritze.skyward.core.model.VisibilityResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class AuroraVisibilityModelTest {

    private val model = AuroraVisibilityModel()
    private val tromso = GeoPoint(69.6492, 18.9553)
    private val berlin = GeoPoint(52.52, 13.405)
    private val calgary = GeoPoint(51.0447, -114.0719)
    private val munich = GeoPoint(48.1351, 11.5820)

    private fun loc(point: GeoPoint) = SavedLocation(
        id = "loc", name = "Test", point = point, isPrimary = true,
        createdAt = Instant.parse("2026-01-01T00:00:00Z"), modifiedAt = Instant.parse("2026-01-01T00:00:00Z"),
    )

    private fun threeDayOccurrence(kp: Double, window: TimeWindow) = Occurrence(
        id = "test-3day",
        phenomenon = Phenomenon.AURORA,
        sourceId = "swpc",
        title = "Aurora",
        window = window,
        peakTime = null,
        certainty = Certainty.FORECAST,
        payload = AuroraPayload(kpForecast = kp, forecastKind = AuroraForecastKind.THREE_DAY, issuedAt = window.start),
        fetchedAt = window.start,
        expiresAt = null,
    )

    private fun nowcastOccurrence(window: TimeWindow) = Occurrence(
        id = "test-nowcast",
        phenomenon = Phenomenon.AURORA,
        sourceId = "swpc",
        title = "Aurora now",
        window = window,
        peakTime = null,
        certainty = Certainty.FORECAST,
        payload = AuroraPayload(kpForecast = 0.0, forecastKind = AuroraForecastKind.NOWCAST, issuedAt = window.start),
        fetchedAt = window.start,
        expiresAt = null,
    )

    // Tromso in mid-December: polar night, so any clock hour is astronomically dark.
    private val tromsoWinterNight = TimeWindow(Instant.parse("2026-12-15T18:00:00Z"), Instant.parse("2026-12-15T22:00:00Z"))
    // Munich at local noon in July: unambiguous broad daylight.
    private val munichSummerNoon = TimeWindow(Instant.parse("2026-07-15T11:00:00Z"), Instant.parse("2026-07-15T13:00:00Z"))
    private val fixtureWindow = TimeWindow(Instant.parse("2026-01-15T00:00:00Z"), Instant.parse("2026-01-15T02:00:00Z"))

    @Test
    fun auroraLocalDetailsFreezeAppendixFixturesForGeomagneticLatitudeAndKpNeeded() {
        data class Fixture(val point: GeoPoint, val expectedGeomagneticLatDeg: Double, val expectedKpNeeded: Double)

        val fixtures = mapOf(
            "Tromso" to Fixture(tromso, 67.5, 0.0),
            "Berlin" to Fixture(berlin, 52.2, 6.9),
            "Calgary" to Fixture(calgary, 57.4, 4.3),
            "Munich" to Fixture(munich, 48.2, 8.9),
        )
        val occ = threeDayOccurrence(kp = 3.0, window = fixtureWindow)

        fixtures.forEach { (name, fixture) ->
            val result = model.evaluate(occ, loc(fixture.point), VisibilityContext(now = fixtureWindow.start, ovationGrid = null))
            val details = assertNotNull(result.localDetails as? LocalDetails.AuroraLocal, name)
            assertEquals(fixture.expectedGeomagneticLatDeg, details.geomagneticLatDeg, 0.2, name)
            assertEquals(fixture.expectedKpNeeded, details.kpNeeded, 0.1, name)
        }
    }

    @Test
    fun highGeomagneticLatitudeAtModerateKpIsExcellentInDarkness() {
        val occ = threeDayOccurrence(kp = 5.0, window = tromsoWinterNight)
        val result = model.evaluate(occ, loc(tromso), VisibilityContext(now = tromsoWinterNight.start, ovationGrid = null))

        assertTrue(result.visibleAtLocation)
        assertEquals(Quality.EXCELLENT, result.quality)
        assertNull(result.travelDistanceKm)
        val details = result.localDetails as? LocalDetails.AuroraLocal
        assertNotNull(details)
        assertEquals(67.5, details.geomagneticLatDeg, 0.2)
    }

    @Test
    fun lowGeomagneticLatitudeGetsTravelGuidanceTowardThePole() {
        val occ = threeDayOccurrence(kp = 3.0, window = tromsoWinterNight) // visLat = 66-6 = 60
        val result = model.evaluate(occ, loc(munich), VisibilityContext(now = tromsoWinterNight.start, ovationGrid = null))

        assertTrue(!result.visibleAtLocation)
        assertEquals(Quality.NONE, result.quality)
        val travelDistanceKm = result.travelDistanceKm
        assertNotNull(travelDistanceKm)
        // Delta gm-lat ~ 60 - 1 - 48.2 = 10.8deg (MARGINAL starts at visLat-1) -> ~1200km, generously bounded.
        assertTrue(travelDistanceKm in 500.0..2500.0, "expected a plausible multi-hundred-km travel distance, got $travelDistanceKm")
        assertEquals(Quality.MARGINAL, result.qualityAtNearestPoint)
    }

    @Test
    fun noDarknessOverlapCapsQualityAtMarginal() {
        // Kp=12 is unrealistically high but forces what would otherwise be
        // EXCELLENT at Munich's modest geomagnetic latitude, isolating the
        // darkness-gate behavior from the Kp-threshold behavior.
        val occ = threeDayOccurrence(kp = 12.0, window = munichSummerNoon)
        val result = model.evaluate(occ, loc(munich), VisibilityContext(now = munichSummerNoon.start, ovationGrid = null))

        assertTrue(result.quality <= Quality.MARGINAL, "expected darkness gate to cap quality, got ${result.quality}")
    }

    @Test
    fun nowcastUsesOvationGridProbabilityThresholds() {
        val grid = uniformGrid(probability = 80)
        val occ = nowcastOccurrence(tromsoWinterNight)
        val result = model.evaluate(occ, loc(tromso), VisibilityContext(now = tromsoWinterNight.start, ovationGrid = grid))

        assertTrue(result.visibleAtLocation)
        assertEquals(Quality.EXCELLENT, result.quality)
        val details = result.localDetails as? LocalDetails.AuroraLocal
        assertNotNull(details)
        assertEquals(80, details.ovationProbability)
    }

    @Test
    fun nowcastBelowThresholdGetsNoneAndNoOvationGridMeansNone() {
        val lowGrid = uniformGrid(probability = 5)
        val occ = nowcastOccurrence(tromsoWinterNight)

        val lowResult = model.evaluate(occ, loc(tromso), VisibilityContext(now = tromsoWinterNight.start, ovationGrid = lowGrid))
        assertEquals(Quality.NONE, lowResult.quality)

        val noGridResult = model.evaluate(occ, loc(tromso), VisibilityContext(now = tromsoWinterNight.start, ovationGrid = null))
        assertEquals(Quality.NONE, noGridResult.quality)
        assertNull(noGridResult.travelDistanceKm, "no grid data means no travel guidance either")
    }

    @Test
    fun nowcastTravelTargetReachesTheGoodThresholdItWasSearchedFor() {
        // Uniformly below-threshold except a high-probability band from
        // latitude 53 north -- close enough to Munich (~556km) to fall
        // within the NOWCAST travel search radius.
        val grid = latitudeBandGrid(highLatThreshold = 53, highProbability = 60, lowProbability = 5)
        val occ = nowcastOccurrence(tromsoWinterNight)
        val result = model.evaluate(occ, loc(munich), VisibilityContext(now = tromsoWinterNight.start, ovationGrid = grid))

        assertTrue(!result.visibleAtLocation)
        assertEquals(Quality.NONE, result.quality)
        assertNotNull(result.travelDistanceKm)
        // The travel target was searched for at the probability>=50 (GOOD)
        // threshold, not the THREE_DAY regime's MARGINAL one -- the two
        // regimes must not share a hardcoded qualityAtNearestPoint.
        assertEquals(Quality.GOOD, result.qualityAtNearestPoint)
    }

    @Test
    fun qualityAndTravelDistanceAreMonotoneInKp() {
        // §17.4's "Kp rule monotonicity". A geomagnetic storm getting
        // stronger can only ever improve a location's prospects, so sweeping
        // Kp upward must never lower the quality, never turn a visible
        // location invisible, and never push the travel target further away.
        // Stated as a property rather than a handful of frozen values because
        // the failure it guards against — an inverted comparison or a
        // mis-signed term in `visLat = 66 - 2*Kp` — can leave any individual
        // sample looking entirely plausible.
        val southernHemisphere = GeoPoint(-45.87, 170.50) // Dunedin, NZ: exercises abs(gmLat)
        val places = mapOf(
            "Tromso" to tromso,
            "Calgary" to calgary,
            "Berlin" to berlin,
            "Munich" to munich,
            "Dunedin" to southernHemisphere,
        )
        // Both windows, because the darkness gate caps quality independently
        // of Kp: monotonicity has to survive the cap, not just hold above it.
        val windows = mapOf("dark" to tromsoWinterNight, "daylit" to munichSummerNoon)

        for ((placeName, point) in places) {
            for ((windowName, window) in windows) {
                val ctx = VisibilityContext(now = window.start, ovationGrid = null)
                var previous: VisibilityResult? = null
                var previousKp = 0.0
                var kp = 0.0
                while (kp <= 9.0) {
                    val result = model.evaluate(threeDayOccurrence(kp, window), loc(point), ctx)
                    val last = previous
                    if (last != null) {
                        val where = "$placeName/$windowName Kp $previousKp -> $kp"
                        assertTrue(
                            result.quality >= last.quality,
                            "$where: quality fell from ${last.quality} to ${result.quality}",
                        )
                        assertTrue(
                            result.visibleAtLocation || !last.visibleAtLocation,
                            "$where: visible at the lower Kp but not the higher one",
                        )
                        val previousTravel = last.travelDistanceKm
                        val currentTravel = result.travelDistanceKm
                        if (previousTravel != null && currentTravel != null) {
                            // 1e-9 absorbs the destination-point trigonometry's
                            // rounding; the assertion is about direction, not
                            // about exact distances.
                            assertTrue(
                                currentTravel <= previousTravel + 1e-9,
                                "$where: travel distance grew from $previousTravel km to $currentTravel km",
                            )
                        }
                    }
                    previous = result
                    previousKp = kp
                    kp += 0.25
                }
            }
        }
    }

    @Test
    fun aStrongEnoughStormEventuallyReachesEveryLatitudeItShould() {
        // Guards the degenerate way monotonicity can hold: a model that
        // returns NONE everywhere is monotone too. Berlin needs Kp 6.9
        // (frozen above), so it must be NONE well below that and visible
        // above it.
        val ctx = VisibilityContext(now = tromsoWinterNight.start, ovationGrid = null)
        val belowThreshold = model.evaluate(threeDayOccurrence(kp = 5.0, window = tromsoWinterNight), loc(berlin), ctx)
        val aboveThreshold = model.evaluate(threeDayOccurrence(kp = 8.0, window = tromsoWinterNight), loc(berlin), ctx)

        assertEquals(Quality.NONE, belowThreshold.quality, "Berlin at Kp 5 is below its own kpNeeded of 6.9")
        assertTrue(aboveThreshold.visibleAtLocation, "Berlin at Kp 8 is past its threshold and should be visible")
    }

    private fun uniformGrid(probability: Int): OvationGrid {
        val bytes = ByteArray(360 * 181) { probability.toByte() }
        return OvationGrid(tromsoWinterNight.start, tromsoWinterNight.start, bytes)
    }

    private fun latitudeBandGrid(highLatThreshold: Int, highProbability: Int, lowProbability: Int): OvationGrid {
        val bytes = ByteArray(360 * 181)
        for (lon in 0 until 360) {
            for (lat in -90..90) {
                bytes[(lon * 181) + (lat + 90)] = (if (lat >= highLatThreshold) highProbability else lowProbability).toByte()
            }
        }
        return OvationGrid(tromsoWinterNight.start, tromsoWinterNight.start, bytes)
    }
}
