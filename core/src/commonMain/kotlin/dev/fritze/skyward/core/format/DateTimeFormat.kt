package dev.fritze.skyward.core.format

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * §5: "timezone conversion happens only at the UI/notification edge" — these
 * are that edge's primitives, and §4.1 puts anything both frontends render in
 * `core/format/`. They take the zone explicitly rather than reading a system
 * default, so the caller stays the one deciding whose local time is meant.
 *
 * Both frontends must agree on these: a diagnostics timestamp that reads
 * "21 Aug 2026, 14:03" on desktop and "2026-08-21T14:03:22.512Z" on Android
 * is the same fact told two ways, and only one of them is readable (#71).
 */

private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

/** Clamped rather than checked: a bad month number should render oddly, not crash a screen. */
fun monthAbbreviation(monthNumber: Int): String = MONTHS[(monthNumber - 1).coerceIn(0, 11)]

// `.month.number` / `.day` are the non-deprecated kotlinx-datetime spellings but
// don't resolve against this project's version; core/sources uses
// monthNumber/dayOfMonth for the same reason. Keep both in step.
fun formatDate(instant: Instant, zone: TimeZone): String = instant.toLocalDateTime(zone).let {
    "${it.dayOfMonth} ${monthAbbreviation(it.monthNumber)} ${it.year}"
}

fun formatDayAndMonth(instant: Instant, zone: TimeZone): String = instant.toLocalDateTime(zone).let {
    "${it.dayOfMonth} ${monthAbbreviation(it.monthNumber)}"
}

fun formatTime(instant: Instant, zone: TimeZone): String = instant.toLocalDateTime(zone).hhmm()

fun formatDateTime(instant: Instant, zone: TimeZone): String = "${formatDate(instant, zone)}, ${formatTime(instant, zone)}"

fun LocalDateTime.hhmm(): String = "${hour.toString().padStart(2, '0')}:${minute.toString().padStart(2, '0')}"
