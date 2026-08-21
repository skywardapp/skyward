package dev.fritze.skyward.desktop.ui.common

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * Presentation-only string helpers with a single caller — the desktop app.
 * §4.1's rule sends anything both frontends render to `core/format/` instead:
 * `phenomenonLabel`, `sourceDisplayName` and the date/time family all live
 * there. What is left here is what desktop alone renders, and each of these
 * moves the same way the moment Android grows a second caller.
 */

/** "in 3 days" / "in 4 h" / "in 25 min" / "now" / "3 days ago" — the countdown used on cards and the timeline. */
fun formatRelative(from: Instant, to: Instant): String {
    val delta = to - from
    val magnitude = abs(delta.inWholeSeconds)
    val text = when {
        magnitude < 60 -> "now"
        magnitude < 3600 -> "${(magnitude / 60)} min"
        magnitude < 86_400 -> "${(magnitude / 3600)} h"
        magnitude < 86_400 * 60 -> plural(magnitude / 86_400, "day")
        else -> plural((magnitude / 86_400 / 30.44).roundToInt().toLong(), "month")
    }
    return when {
        text == "now" -> "now"
        delta.isNegative() -> "$text ago"
        else -> "in $text"
    }
}

fun formatDurationShort(duration: Duration): String {
    val seconds = duration.inWholeSeconds
    return when {
        seconds < 60 -> "${seconds} s"
        seconds < 3600 -> "${seconds / 60} min"
        seconds % 3600 == 0L -> "${seconds / 3600} h"
        else -> "${seconds / 3600} h ${(seconds % 3600) / 60} min"
    }
}

fun formatDistanceKm(km: Double): String = when {
    km < 10 -> "${(km * 10).roundToInt() / 10.0} km"
    km < 1000 -> "${km.roundToInt()} km"
    else -> "${(km / 100).roundToInt() / 10.0} thousand km"
}

/** English "add an s" pluralization, deliberately: docs/adr/0012-english-only-ui-strings.md. */
private fun plural(count: Long, noun: String): String = if (count == 1L) "1 $noun" else "$count ${noun}s"

fun formatDegrees(deg: Double, decimals: Int = 0): String {
    val factor = 10.0.pow(decimals.coerceIn(0, 6))
    return "${(deg * factor).roundToInt() / factor}°"
}

fun formatPercent(fraction: Double): String = "${(fraction * 100).roundToInt()} %"

/**
 * Kp is reported in thirds (4.33, 5.67), so a raw `Double` renders as
 * `5.333333333333333`. Two decimals is the resolution the product actually
 * carries; shared so the Overview badge and §14.4's gauge can't drift apart.
 */
fun formatKp(kp: Double): String = "${(kp * 100).roundToInt() / 100.0}"
