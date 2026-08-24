package dev.fritze.skyward.core.chart

import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/** §14.2's month/year gridlines, and the spacing rule that keeps them readable. */
class MonthTicksTest {

    private val now = Instant.parse("2026-08-24T00:00:00Z")
    private val zone = TimeZone.UTC

    /**
     * The far end of a three-year axis compresses months to a few pixels
     * apart. A January label is forced there regardless of spacing, so
     * without care it prints on top of a December label that had passed the
     * spacing check — which is exactly what a phone-width canvas produces.
     */
    @Test
    fun noTwoLabelsAreDrawnCloserThanTheMinimumSpacing() {
        val scale = TimelineScale(now, now + (365 * 3).days, widthPx = 900f)
        val labelled = monthTicks(scale, zone).filter { it.labelled }

        assertTrue(labelled.size > 4, "expected a populated axis, got ${labelled.size} labels")
        for ((earlier, later) in labelled.zipWithNext()) {
            assertTrue(
                later.x - earlier.x >= DEFAULT_MIN_LABEL_SPACING,
                "labels '${earlier.label}' and '${later.label}' overlap: " +
                    "${later.x - earlier.x}px apart, minimum is $DEFAULT_MIN_LABEL_SPACING",
            )
        }
    }

    /** The year label is the one that survives a collision — it carries more. */
    @Test
    fun everyJanuaryStillCarriesItsYearLabel() {
        val scale = TimelineScale(now, now + (365 * 3).days, widthPx = 900f)
        val years = monthTicks(scale, zone).filter { it.isYearStart }

        assertTrue(years.isNotEmpty(), "a three-year axis must cross at least one January")
        assertTrue(years.all { it.labelled }, "a forced year label must never be dropped")
    }

    @Test
    fun aWiderCanvasLabelsAtLeastAsManyMonths() {
        val narrow = monthTicks(TimelineScale(now, now + 365.days, widthPx = 400f), zone)
        val wide = monthTicks(TimelineScale(now, now + 365.days, widthPx = 1600f), zone)
        assertTrue(wide.count { it.labelled } >= narrow.count { it.labelled })
    }
}
