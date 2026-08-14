package dev.fritze.skyward.desktop.ui.skychart

import androidx.compose.ui.geometry.Offset
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * §14.3: "Stereographic projection of the local sky (azimuth/altitude)".
 *
 * Zenith at the centre, horizon on the rim. Stereographic from the nadir
 * gives `r ∝ tan(z/2)` for zenith distance `z`, which is exactly 1 at the
 * horizon — so the normalised radius *is* `tan(z/2)`, and the rim needs no
 * separate scaling constant.
 *
 * Orientation follows a planisphere held overhead: north up, east to the
 * left. That mirroring is not a bug — a chart of the sky is looked *up* at,
 * so east and west swap relative to a map of the ground.
 */
object SkyProjection {

    /** Null when [altitudeDeg] is below the horizon: there is nothing to draw for a body that has set. */
    fun project(altitudeDeg: Double, azimuthDeg: Double, center: Offset, radiusPx: Float): Offset? {
        if (altitudeDeg < 0.0) return null
        val zenithDistance = (90.0 - altitudeDeg) * PI / 180.0
        val r = tan(zenithDistance / 2.0).toFloat() * radiusPx
        val azimuth = azimuthDeg * PI / 180.0
        return Offset(
            x = center.x - (r * sin(azimuth)).toFloat(),
            y = center.y - (r * cos(azimuth)).toFloat(),
        )
    }

    /** Radius of the constant-altitude ring at [altitudeDeg], for the 30°/60° guides. */
    fun ringRadius(altitudeDeg: Double, radiusPx: Float): Float =
        tan(((90.0 - altitudeDeg) * PI / 180.0) / 2.0).toFloat() * radiusPx

    /** Cardinal points, in the order they are drawn around the horizon. */
    val CARDINALS: List<Pair<String, Double>> = listOf(
        "N" to 0.0,
        "E" to 90.0,
        "S" to 180.0,
        "W" to 270.0,
    )
}
