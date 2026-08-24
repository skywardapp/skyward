package dev.fritze.skyward.ui.chart

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import dev.fritze.skyward.core.chart.AuroraPolarPlot
import dev.fritze.skyward.core.chart.OvationRaster
import dev.fritze.skyward.core.chart.buildLandOutline
import dev.fritze.skyward.core.visibility.OvationGrid

/**
 * §14.1's base layer and aurora overlay as Android Compose types.
 *
 * Everything with a decision in it — the world-coordinate walk, the
 * antimeridian rule, the OVATION pixel arithmetic — is in `:core` (ADR 0021,
 * ADR 0023). What is left here is naming the platform types those results go
 * into, which is the one part that cannot be shared.
 */

/**
 * The base map as a `Path` in world coordinates. Prefer [landPath]: the
 * vectors are a build-time constant, so there is no reason to walk 60 000
 * points again on every recomposition.
 */
fun buildLandPath(): Path = Path().also { path ->
    buildLandOutline(moveTo = path::moveTo, lineTo = path::lineTo, close = path::close)
}

/**
 * The shared, process-wide land path. Built on first use and kept: the
 * Natural Earth vectors cannot change while the app runs, and the `Path` is
 * only ever read.
 */
val landPath: Path by lazy { buildLandPath() }

/** The OVATION overlay as an Android [ImageBitmap]; pixels from [OvationRaster]. */
fun ovationOverlayImage(grid: OvationGrid): ImageBitmap =
    OvationRaster.equirectangularOverlay(grid)
        .toImageBitmap(OvationRaster.OVERLAY_WIDTH, OvationRaster.OVERLAY_HEIGHT)

/** §14.4 Row 2's polar cap as an Android [ImageBitmap]. */
fun polarRasterImage(
    grid: OvationGrid,
    north: Boolean,
    sizePx: Int = AuroraPolarPlot.DEFAULT_RASTER_SIZE,
): ImageBitmap = OvationRaster.polarCap(grid, north, sizePx).toImageBitmap(sizePx, sizePx)

/**
 * `createBitmap(colors, …)` takes the ARGB array directly, so there is no
 * per-pixel loop on this side of the boundary either.
 */
private fun IntArray.toImageBitmap(width: Int, height: Int): ImageBitmap =
    Bitmap.createBitmap(this, width, height, Bitmap.Config.ARGB_8888).asImageBitmap()
