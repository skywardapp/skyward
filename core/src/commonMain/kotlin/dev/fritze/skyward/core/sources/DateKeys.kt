package dev.fritze.skyward.core.sources

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/** UTC-calendar `yyyymmdd` of this instant — shared by the natural-key formats in §6.4. */
internal fun Instant.toYearMonthDayKey(): String {
    val dt = toLocalDateTime(TimeZone.UTC)
    val mm = dt.monthNumber.toString().padStart(2, '0')
    val dd = dt.dayOfMonth.toString().padStart(2, '0')
    return "${dt.year}$mm$dd"
}
