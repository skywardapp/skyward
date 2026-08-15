package dev.fritze.skyward.desktop.ui.aurora

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import dev.fritze.skyward.core.model.GeoPoint
import dev.fritze.skyward.core.visibility.OvationGrid
import dev.fritze.skyward.desktop.ui.common.OvationRamp
import java.awt.image.BufferedImage
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
 * Kept free of Compose runtime types (only geometry/graphics value classes)
 * so the projection can be unit-tested directly.
 */
object AuroraPolarPlot {

    /** Geographic latitude at the rim of the disc. */
    const val RIM_LATITUDE = 45.0

    /**
     * Where [point] lands on a disc of radius [radiusPx] centred at [center].
     * Returns null for a point outside the plotted cap — a location in Spain
     * has no position on a ≥45° plot, and pinning it to the rim would be a lie.
     */
    fun project(point: GeoPoint, center: Offset, radiusPx: Float, north: Boolean): Offset? {
        val latitude = if (north) point.latDeg else -point.latDeg
        if (latitude < RIM_LATITUDE) return null
        val r = ((90.0 - latitude) / (90.0 - RIM_LATITUDE)).toFloat() * radiusPx
        // The south view is a mirror image, not a rotation: looking at the
        // southern cap from below flips the sense of increasing longitude.
        val theta = Math.toRadians(if (north) point.lonDeg else -point.lonDeg)
        return Offset(
            x = center.x + r * sin(theta).toFloat(),
            y = center.y - r * cos(theta).toFloat(),
        )
    }

    /** The inverse of [project], for building the raster: null outside the disc. */
    fun unproject(dx: Double, dy: Double, north: Boolean): GeoPoint? {
        val r = hypot(dx, dy)
        if (r > 1.0) return null
        val latitude = 90.0 - r * (90.0 - RIM_LATITUDE)
        val lonDeg = Math.toDegrees(atan2(dx, -dy))
        return if (north) {
            GeoPoint(latitude, normalizeLongitude(lonDeg))
        } else {
            GeoPoint(-latitude, normalizeLongitude(-lonDeg))
        }
    }

    /**
     * Rasterizes the cap once per (grid, hemisphere) into a square image —
     * the alternative, drawing ~16 000 grid cells as quads every frame, is
     * the same picture at a hundred times the cost.
     */
    fun rasterize(grid: OvationGrid, north: Boolean, sizePx: Int = DEFAULT_RASTER_SIZE): ImageBitmap {
        val image = BufferedImage(sizePx, sizePx, BufferedImage.TYPE_INT_ARGB)
        val center = (sizePx - 1) / 2.0
        for (py in 0 until sizePx) {
            for (px in 0 until sizePx) {
                val dx = (px - center) / center
                val dy = (py - center) / center
                val point = unproject(dx, dy, north) ?: continue
                val probability = grid.probabilityAt(point)
                if (probability < MIN_VISIBLE_PROBABILITY) continue
                image.setRGB(px, py, probabilityArgb(probability))
            }
        }
        return image.toComposeImageBitmap()
    }

    /** The probability ramp, shared by the raster, the colorbar and §14.1's map overlay. */
    fun probabilityArgb(probability: Double): Int = OvationRamp.argb(probability)

    private fun normalizeLongitude(lonDeg: Double): Double {
        val wrapped = ((lonDeg + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
        return wrapped
    }

    const val MIN_VISIBLE_PROBABILITY = OvationRamp.DASHBOARD_MIN_PROBABILITY
    private const val DEFAULT_RASTER_SIZE = 420
}
