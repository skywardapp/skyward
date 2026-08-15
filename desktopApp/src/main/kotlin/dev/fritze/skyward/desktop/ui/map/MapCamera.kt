package dev.fritze.skyward.desktop.ui.map

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import dev.fritze.skyward.core.model.GeoPoint
import kotlin.math.abs

/**
 * §14.1's projection: "custom Compose Canvas, equirectangular projection
 * (lon→x, lat→y linear), pan/zoom via transformable state (clamp zoom
 * 1×–8×)".
 *
 * Deliberately free of any Compose *runtime* dependency (only the geometry
 * value types) so the whole projection is unit-testable — the eclipse-path
 * acceptance check in §18 is ultimately a statement about this arithmetic.
 *
 * [offset] is in pixels and applies after scaling, so the world is
 * `zoom × size` pixels and [offset] is where its top-left corner sits.
 */
data class MapCamera(val zoom: Float = 1f, val offset: Offset = Offset.Zero) {

    fun project(point: GeoPoint, size: Size): Offset = project(point.lonDeg, point.latDeg, size)

    fun project(lonDeg: Double, latDeg: Double, size: Size): Offset = Offset(
        x = ((lonDeg + 180.0) / 360.0).toFloat() * size.width * zoom + offset.x,
        y = ((90.0 - latDeg) / 180.0).toFloat() * size.height * zoom + offset.y,
    )

    fun unproject(screen: Offset, size: Size): GeoPoint {
        val worldX = (screen.x - offset.x) / (size.width * zoom)
        val worldY = (screen.y - offset.y) / (size.height * zoom)
        return GeoPoint(
            latDeg = 90.0 - worldY.toDouble() * 180.0,
            lonDeg = worldX.toDouble() * 360.0 - 180.0,
        )
    }

    /** Pixels per degree of longitude at the current zoom — also the basis for the lat-scaled travel circles. */
    fun pixelsPerLonDegree(size: Size): Float = size.width * zoom / 360f

    fun pixelsPerLatDegree(size: Size): Float = size.height * zoom / 180f

    /**
     * Pans by [delta] and re-clamps. Panning past the edge is not blocked at
     * the gesture level but corrected here, so a fast drag decelerates into
     * the edge instead of stopping dead partway through the gesture.
     */
    fun panned(delta: Offset, size: Size): MapCamera = copy(offset = offset + delta).clamped(size)

    /**
     * Zooms around [focus] (the pointer position) so the geographic point
     * under the cursor stays under the cursor — the behaviour every map has
     * and the only one that doesn't feel broken with a scroll wheel.
     */
    fun zoomed(factor: Float, focus: Offset, size: Size): MapCamera {
        val newZoom = (zoom * factor).coerceIn(MIN_ZOOM, MAX_ZOOM)
        if (newZoom == zoom) return this
        val scale = newZoom / zoom
        // focus = worldPoint * zoom + offset, solved for the offset that keeps
        // worldPoint fixed after scaling.
        return copy(zoom = newZoom, offset = focus - (focus - offset) * scale).clamped(size)
    }

    /**
     * Keeps the world filling the viewport: at 1× it exactly covers it, and
     * beyond that the visible window stays inside the map rather than
     * revealing background beyond the poles or the antimeridian.
     */
    fun clamped(size: Size): MapCamera {
        val worldWidth = size.width * zoom
        val worldHeight = size.height * zoom
        val minX = size.width - worldWidth
        val minY = size.height - worldHeight
        return copy(
            offset = Offset(
                x = offset.x.coerceIn(minOf(minX, 0f), maxOf(minX, 0f)),
                y = offset.y.coerceIn(minOf(minY, 0f), maxOf(minY, 0f)),
            ),
        )
    }

    companion object {
        const val MIN_ZOOM = 1f
        const val MAX_ZOOM = 8f

        /**
         * True when a polyline segment crosses the antimeridian, i.e. its two
         * endpoints are more than half a world apart in longitude. Drawing
         * such a segment would streak a line straight across the map — every
         * eclipse central path that leaves the Pacific hits this.
         */
        fun crossesAntimeridian(lonA: Double, lonB: Double): Boolean = abs(lonA - lonB) > 180.0
    }
}
