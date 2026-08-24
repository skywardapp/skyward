package dev.fritze.skyward.core.chart

import dev.fritze.skyward.core.map.NaturalEarthMap
import kotlin.math.abs

/**
 * §14.1's base map as path commands, in *world* coordinates: x spans
 * `[0, 2]` and y spans `[0, 1]`, so drawing it is a single uniform scale
 * onto a 2:1 viewport and a stroke width means the same thing in both axes.
 *
 * Emitted through callbacks rather than returned as a path because the two
 * frontends build different `Path` types and §4.1 keeps `:core` free of
 * either (ADR 0021). What is actually worth sharing is not the container —
 * it is the walk: 60 000 points, the world-coordinate mapping, and the
 * antimeridian rule that stops Antarctica and Chukotka being closed with a
 * horizontal streak across the map. Each app is then five lines that hand
 * `Path::moveTo`, `Path::lineTo` and `Path::close` to this.
 */
fun buildLandOutline(
    moveTo: (x: Float, y: Float) -> Unit,
    lineTo: (x: Float, y: Float) -> Unit,
    close: () -> Unit,
) {
    for (ring in NaturalEarthMap.landRings) {
        if (ring.pointCount < 2) continue
        var started = false
        var previousLon = 0f
        for (i in 0 until ring.pointCount) {
            val lon = ring.lon(i)
            val lat = ring.lat(i)
            val x = (lon + 180f) / 180f
            val y = (90f - lat) / 180f
            // A ring that wraps the antimeridian would otherwise be closed
            // with a horizontal streak across the map.
            if (started && abs(lon - previousLon) > 180f) {
                moveTo(x, y)
            } else if (started) {
                lineTo(x, y)
            } else {
                moveTo(x, y)
                started = true
            }
            previousLon = lon
        }
        close()
    }
}
