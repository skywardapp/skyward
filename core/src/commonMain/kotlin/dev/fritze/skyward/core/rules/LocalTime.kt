package dev.fritze.skyward.core.rules

import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * "Local" time as local mean solar time (`lonDeg / 15` hours of offset from
 * UTC) rather than a real IANA timezone — see
 * docs/adr/0005-rule-local-time-approximation.md for why. Used only by
 * [Cond.PeakOnWeekend] and [Cond.PeakInLocalHours].
 */
fun approximateLocalDateTime(instant: Instant, lonDeg: Double): LocalDateTime {
    val offsetSeconds = (lonDeg / 15.0) * 3600.0
    return (instant + offsetSeconds.seconds).toLocalDateTime(TimeZone.UTC)
}

/** "weekend" = local Fri 18:00-Mon 06:00 when [includeFridayNight], else Sat 00:00-Mon 00:00 (§9.1). */
fun isLocalWeekend(local: LocalDateTime, includeFridayNight: Boolean): Boolean = when (local.dayOfWeek) {
    DayOfWeek.SATURDAY, DayOfWeek.SUNDAY -> true
    DayOfWeek.FRIDAY -> includeFridayNight && local.hour >= 18
    DayOfWeek.MONDAY -> includeFridayNight && local.hour < 6
    else -> false
}

/** `[fromHour, toHour)`, wrapping midnight when `fromHour > toHour` (§9.1). */
fun isInLocalHourRange(local: LocalDateTime, fromHour: Int, toHour: Int): Boolean =
    if (fromHour <= toHour) local.hour in fromHour until toHour else local.hour >= fromHour || local.hour < toHour
