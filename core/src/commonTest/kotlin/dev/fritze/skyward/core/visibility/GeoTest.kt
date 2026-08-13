package dev.fritze.skyward.core.visibility

import dev.fritze.skyward.core.model.GeoPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GeoTest {

    @Test
    fun oneDegreeAtEquatorIsAboutOneHundredElevenKm() {
        val d = haversineDistanceKm(GeoPoint(0.0, 0.0), GeoPoint(0.0, 1.0))
        assertEquals(111.195, d, 0.001)
    }

    @Test
    fun londonToParisMatchesKnownGreatCircleDistance() {
        val london = GeoPoint(51.5074, -0.1278)
        val paris = GeoPoint(48.8566, 2.3522)
        val d = haversineDistanceKm(london, paris)
        // Commonly cited great-circle distance is ~344 km; assert tightly against
        // an independently computed reference value (spherical haversine, same R).
        assertEquals(343.557, d, 0.01)
    }

    @Test
    fun distanceIsSymmetric() {
        val a = GeoPoint(52.0, 7.6) // Münster — the design doc's worked example (Appendix B)
        val b = GeoPoint(44.84, -0.58) // Bordeaux
        assertEquals(haversineDistanceKm(a, b), haversineDistanceKm(b, a), 1e-9)
    }

    @Test
    fun munsterToBordeauxIsAboutOneThousandKm() {
        // Appendix B's "≈ 300-400 km" figure is a distance-*from-path* value,
        // not this city-to-city distance, so it is not the oracle here.
        // Münster to Bordeaux is ~1000 km; assert the order of magnitude only.
        val d = haversineDistanceKm(GeoPoint(52.0, 7.6), GeoPoint(44.84, -0.58))
        assertTrue(d in 900.0..1100.0, "expected roughly 900-1100 km, got $d")
    }

    @Test
    fun bearingDueEastAtEquatorIsNinety() {
        val bearing = initialBearingDeg(GeoPoint(0.0, 0.0), GeoPoint(0.0, 1.0))
        assertEquals(90.0, bearing, 0.01)
    }

    @Test
    fun bearingDueNorthIsZero() {
        val bearing = initialBearingDeg(GeoPoint(0.0, 0.0), GeoPoint(1.0, 0.0))
        assertEquals(0.0, bearing, 0.01)
    }

    @Test
    fun bearingDueSouthIsOneEighty() {
        val bearing = initialBearingDeg(GeoPoint(10.0, 0.0), GeoPoint(0.0, 0.0))
        assertEquals(180.0, bearing, 0.01)
    }

    @Test
    fun bearingIsAlwaysInRange() {
        val bearing = initialBearingDeg(GeoPoint(10.0, 10.0), GeoPoint(-10.0, -170.0))
        assertTrue(bearing >= 0.0 && bearing < 360.0, "expected [0, 360), got $bearing")
    }

    @Test
    fun destinationPointRoundTripsDistanceAndBearing() {
        val origin = GeoPoint(48.8566, 2.3522) // Paris
        val distanceKm = 500.0
        val bearingDeg = 37.0

        val destination = destinationPoint(origin, distanceKm, bearingDeg)

        assertEquals(distanceKm, haversineDistanceKm(origin, destination), 0.5)
        assertEquals(bearingDeg, initialBearingDeg(origin, destination), 0.1)
    }

    @Test
    fun destinationPointDueEastFromEquatorLandsOnExpectedMeridian() {
        val destination = destinationPoint(GeoPoint(0.0, 0.0), 111.195, 90.0)
        assertEquals(0.0, destination.latDeg, 1e-6)
        assertEquals(1.0, destination.lonDeg, 1e-3)
    }

    @Test
    fun destinationPointNormalizesLongitudeAcrossTheAntimeridian() {
        val destination = destinationPoint(GeoPoint(0.0, 179.5), 111.195, 90.0)
        assertTrue(destination.lonDeg in -180.0..180.0)
        assertEquals(-179.5, destination.lonDeg, 1e-3)
    }

    @Test
    fun zeroDistanceReturnsSamePoint() {
        val origin = GeoPoint(35.0, -12.0)
        val destination = destinationPoint(origin, 0.0, 123.0)
        assertEquals(origin.latDeg, destination.latDeg, 1e-9)
        assertEquals(origin.lonDeg, destination.lonDeg, 1e-9)
    }

    @Test
    fun samePointHasZeroDistance() {
        val p = GeoPoint(12.3, 45.6)
        assertEquals(0.0, haversineDistanceKm(p, p), 1e-9)
    }

    @Test
    fun antipodalPointsAreHalfEarthCircumferenceApart() {
        val a = GeoPoint(10.0, 20.0)
        val b = GeoPoint(-10.0, -160.0) // antipode
        // Half the mean-radius great circle: 6371.0088 * PI ~= 20015.09 km.
        assertEquals(20015.09, haversineDistanceKm(a, b), 0.5)
    }
}
