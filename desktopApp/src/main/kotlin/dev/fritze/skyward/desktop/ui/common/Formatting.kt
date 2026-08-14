package dev.fritze.skyward.desktop.ui.common

import dev.fritze.skyward.core.model.Phenomenon
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * Presentation-only string helpers. §5: "timezone conversion happens only at
 * the UI/notification edge" — this file is the desktop half of that edge
 * (`core/format/` owns the notification half).
 */

/** Kept identical to the Android app's labels so the two frontends agree on names (§4.1). */
fun phenomenonLabel(phenomenon: Phenomenon): String = when (phenomenon) {
    Phenomenon.SOLAR_ECLIPSE -> "Solar eclipse"
    Phenomenon.LUNAR_ECLIPSE -> "Lunar eclipse"
    Phenomenon.AURORA -> "Aurora"
    Phenomenon.METEOR_SHOWER -> "Meteor shower"
    Phenomenon.COMET -> "Comet"
    Phenomenon.MOON_EVENT -> "Supermoon"
    Phenomenon.CONJUNCTION -> "Conjunction"
    Phenomenon.TERRESTRIAL -> "Earth event"
}

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
        magnitude < 86_400 * 60 -> "${(magnitude / 86_400)} days"
        else -> "${(magnitude / 86_400 / 30.44).roundToInt()} months"
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

fun formatDegrees(deg: Double, decimals: Int = 0): String {
    val factor = when (decimals) {
        0 -> 1.0
        1 -> 10.0
        else -> 100.0
    }
    return "${(deg * factor).roundToInt() / factor}°"
}

fun formatPercent(fraction: Double): String = "${(fraction * 100).roundToInt()} %"
