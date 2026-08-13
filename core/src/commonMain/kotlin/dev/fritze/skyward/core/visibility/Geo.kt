package dev.fritze.skyward.core.visibility

import dev.fritze.skyward.core.model.GeoPoint
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Spherical geodesy helpers (§8.1). Deliberately spherical, not ellipsoidal
 * — error is ≤ 0.5%, irrelevant at this app's precision, and pulling in a
 * full geodesy library isn't worth it.
 */

/** Mean Earth radius, km (§8.1). */
const val EARTH_RADIUS_KM = 6371.0088

private fun Double.toRadians() = this * PI / 180.0
private fun Double.toDegrees() = this * 180.0 / PI

/** Great-circle distance between [a] and [b], in km. */
fun haversineDistanceKm(a: GeoPoint, b: GeoPoint): Double {
    val lat1 = a.latDeg.toRadians()
    val lat2 = b.latDeg.toRadians()
    val dLat = (b.latDeg - a.latDeg).toRadians()
    val dLon = (b.lonDeg - a.lonDeg).toRadians()

    val h = sin(dLat / 2) * sin(dLat / 2) +
        cos(lat1) * cos(lat2) * sin(dLon / 2) * sin(dLon / 2)
    val c = 2 * atan2(sqrt(h), sqrt(1 - h))
    return EARTH_RADIUS_KM * c
}

/** Initial (forward) bearing from [a] to [b], degrees clockwise from true north, in `[0, 360)`. */
fun initialBearingDeg(a: GeoPoint, b: GeoPoint): Double {
    val lat1 = a.latDeg.toRadians()
    val lat2 = b.latDeg.toRadians()
    val dLon = (b.lonDeg - a.lonDeg).toRadians()

    val y = sin(dLon) * cos(lat2)
    val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
    val theta = atan2(y, x)
    return (theta.toDegrees() + 360.0) % 360.0
}

/**
 * The point reached from [origin] travelling [distanceKm] along the great
 * circle at initial bearing [bearingDeg] (clockwise from true north).
 */
fun destinationPoint(origin: GeoPoint, distanceKm: Double, bearingDeg: Double): GeoPoint {
    val angularDistance = distanceKm / EARTH_RADIUS_KM
    val lat1 = origin.latDeg.toRadians()
    val lon1 = origin.lonDeg.toRadians()
    val theta = bearingDeg.toRadians()

    val lat2 = asin(
        sin(lat1) * cos(angularDistance) + cos(lat1) * sin(angularDistance) * cos(theta)
    )
    val lon2 = lon1 + atan2(
        sin(theta) * sin(angularDistance) * cos(lat1),
        cos(angularDistance) - sin(lat1) * sin(lat2),
    )

    // Normalize longitude to [-180, 180).
    val normalizedLonDeg = ((lon2.toDegrees() + 540.0) % 360.0) - 180.0
    return GeoPoint(lat2.toDegrees(), normalizedLonDeg)
}

/** Geomagnetic north pole (IGRF-14/WMM2025, epoch 2025) — Appendix D. Drift is ~0.1 deg/yr (§19 R7). */
const val GEOMAGNETIC_POLE_LAT_DEG = 80.85
const val GEOMAGNETIC_POLE_LON_DEG = -72.76
val GEOMAGNETIC_POLE = GeoPoint(GEOMAGNETIC_POLE_LAT_DEG, GEOMAGNETIC_POLE_LON_DEG)

/** Dipole geomagnetic latitude of [p], degrees (Appendix D formula; §8.4). */
fun geomagneticLatitudeDeg(p: GeoPoint): Double {
    val lat = p.latDeg.toRadians()
    val poleLat = GEOMAGNETIC_POLE_LAT_DEG.toRadians()
    val dLon = (p.lonDeg - GEOMAGNETIC_POLE_LON_DEG).toRadians()
    return asin(sin(lat) * sin(poleLat) + cos(lat) * cos(poleLat) * cos(dLon)).toDegrees()
}
