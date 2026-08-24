package dev.fritze.skyward.ui.chart

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import dev.fritze.skyward.core.chart.AuroraPolarPlot
import dev.fritze.skyward.core.chart.OvationRaster
import dev.fritze.skyward.core.map.NaturalEarthMap
import dev.fritze.skyward.core.visibility.OvationGrid
import kotlin.math.abs

/**
 * §14.1's base layer and aurora overlay, as Android Compose types.
 *
 * The desktop app has the same two functions over `BufferedImage` and its
 * own `Path`; what they share — the pixel arithmetic and the ring data —
 * lives in `:core` (ADR 0021, ADR 0023), and this is the part that has to
 * be written per platform because it names a platform image type.
 */

/**
 * The base map as a `Path` in *world* coordinates: x spans `[0, 2]` and y
 * spans `[0, 1]`, so drawing it is a single uniform scale onto a 2:1
 * viewport and a stroke width means the same thing in both axes.
 *
 * Prefer [landPath] — the vectors are a build-time constant, so there is no
 * reason to walk 60 000 points again on every recomposition.
 */
fun buildLandPath(): Path {
    val path = Path()
    for (ring in NaturalEarthMap.landRings) {
        if (ring.pointCount < 2) continue
        var started = false
        var previousLon = 0f
        for (i in 0 until ring.pointCount) {
            val lon = ring.lon(i)
            val lat = ring.lat(i)
            val x = (lon + 180f) / 180f
            val y = (90f - lat) / 180f
            // A ring that wraps the antimeridian (Antarctica, Chukotka) would
            // otherwise be closed with a horizontal streak across the map.
            if (started && abs(lon - previousLon) > 180f) {
                path.moveTo(x, y)
            } else if (started) {
                path.lineTo(x, y)
            } else {
                path.moveTo(x, y)
                started = true
            }
            previousLon = lon
        }
        path.close()
    }
    return path
}

/**
 * The shared, process-wide land path. Built on first use and kept: the
 * Natural Earth vectors cannot change while the app runs, and the `Path` is
 * only ever read.
 */
val landPath: Path by lazy { buildLandPath() }

/** The OVATION overlay as an Android [ImageBitmap]; pixels from [OvationRaster]. */
fun ovationOverlayImage(grid: OvationGrid): ImageBitmap {
    val pixels = OvationRaster.equirectangularOverlay(grid)
    return pixels.toImageBitmap(OvationRaster.OVERLAY_WIDTH, OvationRaster.OVERLAY_HEIGHT)
}

/** §14.4 Row 2's polar cap as an Android [ImageBitmap]. */
fun polarRasterImage(
    grid: OvationGrid,
    north: Boolean,
    sizePx: Int = AuroraPolarPlot.DEFAULT_RASTER_SIZE,
): ImageBitmap {
    val pixels = OvationRaster.polarCap(grid, north, sizePx)
    return pixels.toImageBitmap(sizePx, sizePx)
}

/**
 * `createBitmap(colors, …)` takes the ARGB array directly, so there is no
 * per-pixel loop on this side of the boundary either.
 */
private fun IntArray.toImageBitmap(width: Int, height: Int): ImageBitmap =
    Bitmap.createBitmap(this, width, height, Bitmap.Config.ARGB_8888).asImageBitmap()
