package dev.fritze.skyward.core.chart

import dev.fritze.skyward.core.model.GeoPoint
import dev.fritze.skyward.core.visibility.toDegrees
import dev.fritze.skyward.core.visibility.toRadians
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * §14.4 Row 2: "OVATION polar view — north-polar azimuthal plot of the grid
 * ≥ 45° N (and a south view toggle), user locations overlaid; probability
 * colorbar."
 *
 * Azimuthal equidistant about the pole: radius is linear in colatitude, so
 * the outer rim is 45° geographic latitude and the centre is the pole.
 * Longitude 0 points up; east runs clockwise on the north view.
 *
 * Projection only — the rasterizer that turns a grid into pixels lives in
 * [OvationRaster], and turning those pixels into a platform image is each
 * frontend's job.
 */
object AuroraPolarPlot {

    /** Geographic latitude at the rim of the disc. */
    const val RIM_LATITUDE = 45.0

    /**
     * Where [point] lands on a disc of radius [radiusPx] centred at [center].
     * Returns null for a point outside the plotted cap — a location in Spain
     * has no position on a ≥45° plot, and pinning it to the rim would be a lie.
     */
    fun project(point: GeoPoint, center: ChartPoint, radiusPx: Float, north: Boolean): ChartPoint? {
        val latitude = if (north) point.latDeg else -point.latDeg
        if (latitude < RIM_LATITUDE) return null
        val r = ((90.0 - latitude) / (90.0 - RIM_LATITUDE)).toFloat() * radiusPx
        // The south view is a mirror image, not a rotation: looking at the
        // southern cap from below flips the sense of increasing longitude.
        val theta = (if (north) point.lonDeg else -point.lonDeg).toRadians()
        return ChartPoint(
            x = center.x + r * sin(theta).toFloat(),
            y = center.y - r * cos(theta).toFloat(),
        )
    }

    /** The inverse of [project], for building the raster: null outside the disc. */
    fun unproject(dx: Double, dy: Double, north: Boolean): GeoPoint? {
        val r = hypot(dx, dy)
        if (r > 1.0) return null
        val latitude = 90.0 - r * (90.0 - RIM_LATITUDE)
        val lonDeg = atan2(dx, -dy).toDegrees()
        return if (north) {
            GeoPoint(latitude, normalizeLongitude(lonDeg))
        } else {
            GeoPoint(-latitude, normalizeLongitude(-lonDeg))
        }
    }

    private fun normalizeLongitude(lonDeg: Double): Double =
        ((lonDeg + 180.0) % 360.0 + 360.0) % 360.0 - 180.0

    /** The probability ramp, shared by the raster, the colorbar and §14.1's map overlay. */
    fun probabilityArgb(probability: Double): Int = OvationRamp.argb(probability)

    const val MIN_VISIBLE_PROBABILITY = OvationRamp.DASHBOARD_MIN_PROBABILITY

    /** Side length of the rasterized cap, in pixels. */
    const val DEFAULT_RASTER_SIZE = 420
}
