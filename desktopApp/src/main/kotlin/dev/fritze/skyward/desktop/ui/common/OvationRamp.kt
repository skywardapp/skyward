package dev.fritze.skyward.desktop.ui.common

/**
 * The one OVATION probability ramp, shared by §14.4's polar plot (and its
 * colorbar) and §14.1's map overlay. Two copies of these coefficients drifted
 * apart the moment either was touched, and a legend that disagrees with the
 * raster it labels is worse than no legend.
 *
 * Green → yellow → red, alpha rising with probability. Chosen to stay
 * readable over dark land at low probabilities, where most of the grid sits.
 */
object OvationRamp {

    fun argb(probability: Double): Int {
        val fraction = probability.coerceIn(0.0, 100.0) / 100.0
        val red = (60 + 195 * fraction).toInt().coerceIn(0, 255)
        val green = (220 - 80 * fraction).toInt().coerceIn(0, 255)
        val blue = (120 - 100 * fraction).toInt().coerceIn(0, 255)
        val alpha = (65 + 165 * fraction).toInt().coerceIn(0, 255)
        return (alpha shl 24) or (red shl 16) or (green shl 8) or blue
    }

    /**
     * The dashboard is *about* the aurora, so it shows the faint outer edge of
     * the oval; the map overlay competes with the land, eclipse tracks and
     * markers, so it starts where the oval is actually worth looking at.
     */
    const val DASHBOARD_MIN_PROBABILITY = 5.0
    const val MAP_MIN_PROBABILITY = 10.0
}
