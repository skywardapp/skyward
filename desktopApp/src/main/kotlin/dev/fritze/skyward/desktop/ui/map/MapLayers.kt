package dev.fritze.skyward.desktop.ui.map

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.toComposeImageBitmap
import dev.fritze.skyward.core.chart.OvationRaster
import dev.fritze.skyward.core.chart.buildLandOutline
import dev.fritze.skyward.core.visibility.OvationGrid
import java.awt.image.BufferedImage

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
 * The base map as a `Path` in world coordinates. The walk itself — and the
 * antimeridian rule in it — is `:core`'s [buildLandOutline]; this names the
 * Compose type it fills.
 *
 * Prefer [landPath] over calling this: the vectors never change, so there is
 * no reason to walk 60 000 points again each time the Map tab is opened.
 */
fun buildLandPath(): Path = Path().also { path ->
    buildLandOutline(moveTo = path::moveTo, lineTo = path::lineTo, close = path::close)
}

/**
 * The shared, process-wide land path. Built on first use and kept: the
 * Natural Earth vectors are a build-time resource that cannot change while
 * the app runs, and the `Path` is only ever read.
 */
val landPath: Path by lazy { buildLandPath() }
