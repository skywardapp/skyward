package dev.fritze.skyward.desktop.ui.common

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * Presentation-only string helpers. §5: "timezone conversion happens only at
 * the UI/notification edge" — this file is the desktop half of that edge
 * (`core/format/` owns the notification half, plus anything both frontends
 * render — `phenomenonLabel` moved there, per §4.1).
 */

private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

fun monthAbbreviation(monthNumber: Int): String = MONTHS[(monthNumber - 1).coerceIn(0, 11)]

// `.month.number` / `.day` are the non-deprecated kotlinx-datetime spellings but
// don't resolve against this project's version; core/format and core/sources use
// monthNumber/dayOfMonth for the same reason. Keep all three in step.
fun formatDate(instant: Instant, zone: TimeZone): String = instant.toLocalDateTime(zone).let {
    "${it.dayOfMonth} ${monthAbbreviation(it.monthNumber)} ${it.year}"
}

fun formatDayAndMonth(instant: Instant, zone: TimeZone): String = instant.toLocalDateTime(zone).let {
    "${it.dayOfMonth} ${monthAbbreviation(it.monthNumber)}"
}

fun formatTime(instant: Instant, zone: TimeZone): String = instant.toLocalDateTime(zone).hhmm()

fun formatDateTime(instant: Instant, zone: TimeZone): String = "${formatDate(instant, zone)}, ${formatTime(instant, zone)}"

fun LocalDateTime.hhmm(): String = "${hour.toString().padStart(2, '0')}:${minute.toString().padStart(2, '0')}"

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
