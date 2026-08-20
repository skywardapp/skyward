package dev.fritze.skyward.core.sources

import dev.fritze.skyward.core.model.SavedLocation
import dev.fritze.skyward.core.visibility.EARTH_RADIUS_KM
import dev.fritze.skyward.core.visibility.haversineDistanceKm
import dev.fritze.skyward.core.visibility.toDegrees
import dev.fritze.skyward.core.visibility.toRadians
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/*
 * §7.7 third bullet: when the user's saved locations form a tight cluster,
 * EONET requests carry a `bbox` covering all of them padded by the largest
 * travel radius any enabled rule cares about, so the response doesn't have
 * to carry the whole planet's open events.
 *
 * The bbox is a payload optimization, so every judgement call here is made
 * in the direction that cannot lose events: it covers *all* saved locations
 * (never a subset), it is padded by `maxTravelKm` (the loosest
 * `ReachableWithin` across enabled rules, §6.1), and any shape EONET's
 * two-corner box cannot express is widened rather than approximated. The
 * two conditions below are tighter than §7.7 words them; see
 * docs/adr/0008-eonet-bbox-narrowing-conditions.md.
 */

/**
 * §7.7: the cluster diameter above which a bbox stops being worth sending.
 * Beyond it the box covering every saved location approaches the whole
 * globe anyway.
 */
internal const val EONET_BBOX_CLUSTER_LIMIT_KM = 2_000.0

/**
 * A bbox in EONET's own axis order. Named for that order rather than the
 * usual min/max pairs because §7.7 calls out in bold that EONET is
 * nonstandard here: `bbox=minLon,maxLat,maxLon,minLat` (upper-left corner
 * first, then lower-right), not the `minLon,minLat,maxLon,maxLat` of GeoJSON
 * and almost everything else.
 */
internal data class EonetBbox(
    val minLon: Double,
    val maxLat: Double,
    val maxLon: Double,
    val minLat: Double,
) {
    /** The `bbox=` query value, in the order documented on the class. */
    fun toQueryValue(): String = listOf(minLon, maxLat, maxLon, minLat).joinToString(",") { it.toString() }
}

/**
 * The bbox for [locations] padded by [thresholds]' travel radius, or `null`
 * when the request should go out unnarrowed.
 *
 * Returns `null` when:
 * - there are fewer than two saved locations — §7.7 gates the optimization
 *   on `>= 2`;
 * - any two saved locations are more than [EONET_BBOX_CLUSTER_LIMIT_KM]
 *   apart. §7.7's wording ("if >= 2 saved locations are within 2 000 km of
 *   each other") would also be satisfied by one tight pair plus a distant
 *   outlier, but the box still has to cover the outlier, so narrowing then
 *   buys nothing while risking a lot (ADR 0008);
 * - some enabled rule would match terrestrial occurrences at any distance,
 *   so no box can be drawn without costing it matches — including the case
 *   where no rule uses `ReachableWithin` at all, leaving nothing to pad
 *   with (ADR 0008).
 */
internal fun eonetBbox(locations: List<SavedLocation>, thresholds: DerivedThresholds): EonetBbox? {
    if (locations.size < 2 || !thresholds.terrestrialRulesAreTravelBounded) return null
    // Non-null whenever the flag is set (it takes a `ReachableWithin` to set
    // it), but the padding is what makes the box safe, so it is checked
    // rather than asserted.
    val maxTravelKm = thresholds.maxTravelKm ?: return null

    val points = locations.map { it.point }
    for (i in points.indices) {
        for (j in i + 1 until points.size) {
            if (haversineDistanceKm(points[i], points[j]) > EONET_BBOX_CLUSTER_LIMIT_KM) return null
        }
    }

    val padRad = (maxTravelKm.coerceAtLeast(0.0) / EARTH_RADIUS_KM)
    val padDeg = padRad.toDegrees()

    var minLat = 90.0
    var maxLat = -90.0
    // Longitudes are accumulated relative to the first location so that a
    // cluster straddling the antimeridian stays contiguous; they are folded
    // back to absolute values at the end.
    val anchorLon = points.first().lonDeg
    var minRelLon = Double.MAX_VALUE
    var maxRelLon = -Double.MAX_VALUE
    // Set when the padded circle round some location swallows a pole, where
    // every longitude is within travel range and no lon interval is correct.
    var allLongitudes = padRad >= PI / 2

    for (p in points) {
        minLat = minOf(minLat, p.latDeg - padDeg)
        maxLat = maxOf(maxLat, p.latDeg + padDeg)

        // Widest longitude offset of a circle of angular radius `padRad`
        // centred at this latitude (the tangent meridian, not the naive
        // padDeg / cos(lat), which under-covers near the poles).
        val cosLat = cos(p.latDeg.toRadians())
        val sinRatio = if (cosLat <= 0.0) 2.0 else sin(padRad) / cosLat
        if (sinRatio >= 1.0) {
            allLongitudes = true
        } else {
            val lonPadDeg = asin(sinRatio).toDegrees()
            val relLon = normalizeLonDeg(p.lonDeg - anchorLon)
            minRelLon = minOf(minRelLon, relLon - lonPadDeg)
            maxRelLon = maxOf(maxRelLon, relLon + lonPadDeg)
        }
    }

    minLat = minLat.roundOut(outwardIsDown = true)
    maxLat = maxLat.roundOut(outwardIsDown = false)
    if (maxLat >= 90.0 || minLat <= -90.0) allLongitudes = true
    minLat = minLat.coerceIn(-90.0, 90.0)
    maxLat = maxLat.coerceIn(-90.0, 90.0)

    if (allLongitudes || maxRelLon - minRelLon >= 360.0) {
        return EonetBbox(minLon = -180.0, maxLat = maxLat, maxLon = 180.0, minLat = minLat)
    }

    val minLon = normalizeLonDeg(anchorLon + minRelLon).roundOut(outwardIsDown = true)
    val maxLon = normalizeLonDeg(anchorLon + maxRelLon).roundOut(outwardIsDown = false)
    // A box that wraps the antimeridian comes out as minLon > maxLon, which
    // a two-corner bbox cannot express (and EONET does not document a
    // wrapping form). Keep the latitude band -- still most of the saving --
    // and give up on narrowing longitude.
    if (minLon > maxLon) {
        return EonetBbox(minLon = -180.0, maxLat = maxLat, maxLon = 180.0, minLat = minLat)
    }
    return EonetBbox(minLon = minLon, maxLat = maxLat, maxLon = maxLon, minLat = minLat)
}

/** Folds a longitude (or longitude difference) into `[-180, 180)`. */
private fun normalizeLonDeg(lonDeg: Double): Double = ((lonDeg + 540.0) % 360.0) - 180.0

/**
 * Snaps an edge to three decimals (~100 m), always *away* from the box's
 * interior. Rounding at all keeps the URL -- and so the request -- identical
 * across runs for identical inputs (§17.6); rounding outward rather than to
 * nearest means the snap can only ever add margin, so an event sitting
 * exactly on the travel radius cannot be rounded out of the response.
 */
private fun Double.roundOut(outwardIsDown: Boolean): Double =
    if (outwardIsDown) floor(this * 1000) / 1000.0 else ceil(this * 1000) / 1000.0
