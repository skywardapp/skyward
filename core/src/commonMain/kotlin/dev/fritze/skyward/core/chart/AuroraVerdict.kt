package dev.fritze.skyward.core.chart

import dev.fritze.skyward.core.format.auroraLookDirection
import dev.fritze.skyward.core.format.formatDegrees
import dev.fritze.skyward.core.format.formatKp
import dev.fritze.skyward.core.model.GeoPoint
import dev.fritze.skyward.core.visibility.geomagneticLatitudeDeg
import kotlin.math.abs

/**
 * §14.4 Row 3's per-location verdict, as data and a sentence.
 *
 * Both dashboards printed the same `when` over the same four cases; §4.1
 * puts exactly this kind of presentational helper in `:core` as a pure
 * function, and `core/format` already holds the phrases it composes.
 */
data class AuroraVerdict(
    val geomagneticLatitudeDeg: Double,
    /** §8.4 inverted: visible when `|λgm| >= 66 - 2*Kp`, so `Kp_needed = (66 - |λgm|)/2`. */
    val kpNeeded: Double,
    /** How far the current reading clears [kpNeeded]; null with no live Kp. */
    val marginKp: Double?,
) {
    /** True when the live reading already clears the threshold — the colouring cue. */
    val isVisibleNow: Boolean get() = marginKp != null && marginKp >= 0
}

fun auroraVerdict(point: GeoPoint, currentKp: Double?): AuroraVerdict {
    val geomagneticLat = geomagneticLatitudeDeg(point)
    val kpNeeded = (66.0 - abs(geomagneticLat)) / 2.0
    return AuroraVerdict(geomagneticLat, kpNeeded, currentKp?.let { it - kpNeeded })
}

/** "Geomagnetic latitude 58.2° — visible from here when Kp ≥ 3.9". */
fun auroraThresholdSentence(verdict: AuroraVerdict): String =
    "Geomagnetic latitude ${formatDegrees(verdict.geomagneticLatitudeDeg, 1)} — " +
        "visible from here when Kp ≥ ${formatKp(verdict.kpNeeded)}"

/**
 * What to say about right now, given the live reading and the best slot in
 * the stored forecast. [peakForecastKp] is null when §7.3.3 stored nothing —
 * which means "below every enabled rule's threshold", not "no data".
 */
fun auroraNowSentence(verdict: AuroraVerdict, peakForecastKp: Double?): String {
    val margin = verdict.marginKp
    // The live reading, recovered from the margin rather than passed in
    // again: `marginKp = currentKp - kpNeeded` by construction, so inside
    // these branches it is known and non-null.
    val currentKp = margin?.let { verdict.kpNeeded + it }
    return when {
        verdict.kpNeeded <= 0 -> "Above the auroral boundary at any Kp."
        margin == null && peakForecastKp == null ->
            "No current Kp reading and no forecast slot above your thresholds."
        margin != null && currentKp != null && margin >= 0 ->
            "Now: Kp ${formatKp(currentKp)} — ${formatDegrees(margin * 2, 1)} of margin. " +
                "Look ${auroraLookDirection(verdict.geomagneticLatitudeDeg)} after dark."
        margin != null && currentKp != null ->
            "Now: Kp ${formatKp(currentKp)} — short by ${formatKp(abs(margin))} Kp."
        // Reached only when there is no live reading but a forecast slot exists.
        else -> "Forecast peak Kp ${formatKp(peakForecastKp ?: 0.0)} over the next three days."
    }
}

/**
 * §14.4 Row 2's graticule: the geographic latitudes drawn as rings inside
 * the cap. The rim itself is [AuroraPolarPlot.RIM_LATITUDE] and is drawn as
 * the outermost of these.
 */
val POLAR_GRATICULE_LATITUDES: List<Double> = listOf(AuroraPolarPlot.RIM_LATITUDE, 60.0, 75.0)

/** Radius of the constant-latitude ring at [latitudeDeg] on a cap of [radiusPx]. */
fun polarRingRadius(latitudeDeg: Double, radiusPx: Float): Float =
    ((90.0 - latitudeDeg) / (90.0 - AuroraPolarPlot.RIM_LATITUDE)).toFloat() * radiusPx

/**
 * §14.4 Row 1's gauge is a 180° dial from Kp 0 (left) to Kp 9 (right).
 * Returns the needle angle in radians for [kp], clamped to the dial.
 */
fun kpGaugeAngleRadians(kp: Double): Double {
    val fraction = (kp / 9.0).coerceIn(0.0, 1.0)
    return (180.0 + fraction * 180.0) * kotlin.math.PI / 180.0
}

/** Bar height as a fraction of the strip, for §14.4's 24×3h forecast bars. */
fun forecastBarFraction(kp: Double): Double = (kp / 9.0).coerceIn(0.0, 1.0)
