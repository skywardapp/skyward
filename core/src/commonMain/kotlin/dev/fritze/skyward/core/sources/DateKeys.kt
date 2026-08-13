package dev.fritze.skyward.core.sources

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/** UTC-calendar `yyyymmdd` of this instant — shared by the natural-key formats in §6.4. */
internal fun Instant.toYearMonthDayKey(): String {
    val dt = toLocalDateTime(TimeZone.UTC)
    // dt.month.number / dt.day would be the non-deprecated spelling, but this
    // kotlinx-datetime version's `Month` overload resolution is ambiguous
    // here (kotlinx.datetime.Month vs java.time.Month) and fails to compile;
    // monthNumber/dayOfMonth are deprecated but functionally identical.
    @Suppress("DEPRECATION")
    val mm = dt.monthNumber.toString().padStart(2, '0')
    @Suppress("DEPRECATION")
    val dd = dt.dayOfMonth.toString().padStart(2, '0')
    return "${dt.year}$mm$dd"
}
