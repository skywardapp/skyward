package dev.fritze.skyward.core.chart

import dev.fritze.skyward.core.format.monthAbbreviation
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

/** One gridline on §14.2's time axis, with the label the axis should print there. */
data class MonthTick(val x: Float, val label: String, val isYearStart: Boolean, val labelled: Boolean)

/**
 * §14.2's "month/year grid lines", placed along a [TimelineScale].
 *
 * Month labels are dropped where the compressed far end would overprint them
 * — a year label every January is still readable there, and an unreadable
 * axis is worse than a sparse one. [minLabelSpacing] is in pixels, so a
 * narrow phone canvas can ask for a sparser axis than a desktop pane.
 */
fun monthTicks(
    scale: TimelineScale,
    zone: TimeZone,
    minLabelSpacing: Float = DEFAULT_MIN_LABEL_SPACING,
): List<MonthTick> {
    val ticks = mutableListOf<MonthTick>()
    // `.month.number` / `.day` are the non-deprecated kotlinx-datetime
    // spellings but don't resolve against this project's version — see the
    // note in core/format/Presentation.kt. Keep these in step with it.
    var date = scale.now.toLocalDateTime(zone).date.let { LocalDate(it.year, it.monthNumber, 1) }
        .plus(1, DateTimeUnit.MONTH)
    var lastLabelledX = Float.NEGATIVE_INFINITY
    while (true) {
        val instant = LocalDateTime(date, LocalTime(0, 0)).toInstant(zone)
        if (instant > scale.end) break
        val x = scale.xOf(instant)
        val isYearStart = date.monthNumber == 1
        val labelled = isYearStart || (x - lastLabelledX) >= minLabelSpacing
        if (labelled) {
            // A January label is forced regardless of spacing, so in the
            // compressed far end it can land a few pixels after a December
            // label that passed the spacing check — and the axis draws both,
            // overprinting. The year outranks the month, so drop the earlier
            // label rather than the year's.
            if (isYearStart) {
                val previous = ticks.indexOfLast { it.labelled }
                if (previous >= 0 && x - ticks[previous].x < minLabelSpacing) {
                    ticks[previous] = ticks[previous].copy(labelled = false)
                }
            }
            lastLabelledX = x
        }
        ticks += MonthTick(
            x = x,
            label = if (isYearStart) date.year.toString() else monthAbbreviation(date.monthNumber),
            isYearStart = isYearStart,
            labelled = labelled,
        )
        date = date.plus(1, DateTimeUnit.MONTH)
    }
    return ticks
}

const val DEFAULT_MIN_LABEL_SPACING = 46f
