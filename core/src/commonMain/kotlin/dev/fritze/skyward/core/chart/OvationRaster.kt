package dev.fritze.skyward.core.chart

import dev.fritze.skyward.core.visibility.OvationGrid

/**
 * The OVATION grid as ARGB pixels, for §14.1's map overlay and §14.4's polar
 * view.
 *
 * Both views rasterize once per grid rather than drawing tens of thousands
 * of cells every frame — the same picture at a hundred times the cost. What
 * lives here is the pixel loop, which is arithmetic over
 * [OvationGrid.probabilityAt] and nothing else; wrapping the result in a
 * platform image type (`BufferedImage` on desktop, `Bitmap` on Android) is
 * the frontend's job, and is the only genuinely unshareable part.
 *
 * Pixels are packed ARGB in row-major order, which is what both
 * `BufferedImage.setRGB` and `Bitmap.createBitmap` expect. A cell below its
 * view's threshold is left fully transparent (0) rather than skipped, since
 * an array has no "skip".
 */
object OvationRaster {

    /**
     * §14.1: "aurora OVATION heat overlay (current grid, alpha-blended cells
     * ≥ 10 %, geographic grid drawn directly — trivially aligned with
     * equirectangular)".
     *
     * At equirectangular projection one grid cell is one pixel, so scaling
     * the resulting 360×181 image *is* the projection.
     */
    const val OVERLAY_WIDTH = 360
    const val OVERLAY_HEIGHT = 181

    fun equirectangularOverlay(grid: OvationGrid): IntArray {
        val pixels = IntArray(OVERLAY_WIDTH * OVERLAY_HEIGHT)
        for (x in 0 until OVERLAY_WIDTH) {
            // The grid is indexed 0..359 east of Greenwich; the map starts at
            // -180. Rolling by half a world is the whole conversion.
            val gridLon = (x + 180) % 360
            for (y in 0 until OVERLAY_HEIGHT) {
                val latitude = 90 - y
                val probability = grid.probabilityAt(gridLon, latitude)
                if (probability < OvationRamp.MAP_MIN_PROBABILITY) continue
                pixels[y * OVERLAY_WIDTH + x] = OvationRamp.argb(probability.toDouble())
            }
        }
        return pixels
    }

    /**
     * §14.4 Row 2's polar cap: a square [sizePx]×[sizePx] raster of the
     * azimuthal-equidistant projection, transparent outside the disc.
     */
    fun polarCap(grid: OvationGrid, north: Boolean, sizePx: Int = AuroraPolarPlot.DEFAULT_RASTER_SIZE): IntArray {
        val pixels = IntArray(sizePx * sizePx)
        val center = (sizePx - 1) / 2.0
        for (py in 0 until sizePx) {
            for (px in 0 until sizePx) {
                val dx = (px - center) / center
                val dy = (py - center) / center
                val point = AuroraPolarPlot.unproject(dx, dy, north) ?: continue
                val probability = grid.probabilityAt(point)
                if (probability < AuroraPolarPlot.MIN_VISIBLE_PROBABILITY) continue
                pixels[py * sizePx + px] = OvationRamp.argb(probability)
            }
        }
        return pixels
    }
}
