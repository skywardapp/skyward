package dev.fritze.skyward.desktop.ui.aurora

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import dev.fritze.skyward.core.chart.AuroraPolarPlot
import dev.fritze.skyward.core.chart.OvationRaster
import dev.fritze.skyward.core.visibility.OvationGrid
import java.awt.image.BufferedImage

/**
 * §14.4 Row 2's polar cap, as a desktop [ImageBitmap].
 *
 * The projection and the pixel loop are both in `:core` ([AuroraPolarPlot],
 * [OvationRaster]); all that is left here is handing the finished ARGB array
 * to the one image type this platform builds from — which is the only part
 * of it that cannot be shared.
 */
fun polarRasterImage(grid: OvationGrid, north: Boolean, sizePx: Int = AuroraPolarPlot.DEFAULT_RASTER_SIZE): ImageBitmap {
    val pixels = OvationRaster.polarCap(grid, north, sizePx)
    val image = BufferedImage(sizePx, sizePx, BufferedImage.TYPE_INT_ARGB)
    // setRGB over the whole raster in one call: the per-pixel overload was
    // measured as the slower half of building this image.
    image.setRGB(0, 0, sizePx, sizePx, pixels, 0, sizePx)
    return image.toComposeImageBitmap()
}
