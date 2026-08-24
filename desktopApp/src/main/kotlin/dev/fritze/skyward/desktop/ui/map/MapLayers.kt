package dev.fritze.skyward.desktop.ui.map

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.toComposeImageBitmap
import dev.fritze.skyward.core.chart.OvationRaster
import dev.fritze.skyward.core.map.NaturalEarthMap
import dev.fritze.skyward.core.visibility.OvationGrid
import java.awt.image.BufferedImage
import kotlin.math.abs

/**
 * What is left of §14.1's layer building after the projection-and-geometry
 * half moved to `:core`'s `chart` package: the two pieces that cannot follow
 * it, because each produces a Compose type.
 *
 * The layer set, the eclipse polylines, the EONET markers and the travel
 * radii are all in `dev.fritze.skyward.core.chart.MapLayers` now.
 */

/**
 * The OVATION overlay as a desktop [ImageBitmap]. The pixels come from
 * [OvationRaster]; this hands them to the one image type this platform
 * builds from.
 */
fun ovationOverlayImage(grid: OvationGrid): ImageBitmap {
    val pixels = OvationRaster.equirectangularOverlay(grid)
    val width = OvationRaster.OVERLAY_WIDTH
    val height = OvationRaster.OVERLAY_HEIGHT
    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    image.setRGB(0, 0, width, height, pixels, 0, width)
    return image.toComposeImageBitmap()
}

/**
 * The base map as a `Path` in *world* coordinates, so it is built once and
 * reused for every frame with pan and zoom applied as a canvas transform
 * rather than by rebuilding 60 000 points.
 *
 * World coordinates are degrees over 180: x spans `[0, 2]` and y spans
 * `[0, 1]`. That makes the canvas transform a single uniform scale (the map
 * viewport is drawn 2:1, §14.1's equirectangular projection), which in turn
 * means a stroke width means the same thing horizontally and vertically —
 * it would not with an x-normalised-to-1 path on a 2:1 canvas.
 *
 * Prefer [landPath] over calling this: the vectors never change, so there is
 * no reason to walk 60 000 points again each time the Map tab is opened.
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
 * Natural Earth vectors are a build-time resource that cannot change while
 * the app runs, and the `Path` is only ever read.
 */
val landPath: Path by lazy { buildLandPath() }
