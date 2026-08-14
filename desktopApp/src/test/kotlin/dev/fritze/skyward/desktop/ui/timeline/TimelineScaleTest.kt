package dev.fritze.skyward.desktop.ui.timeline

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/** §14.2: "now → horizon end, log-ish zoom: next 60 days get half the width". */
class TimelineScaleTest {

    private val now = Instant.parse("2026-08-14T00:00:00Z")
    private val end = now + (365 * 3).days
    private val width = 1000f
    private val scale = TimelineScale(now, end, width)

    @Test
    fun theNextSixtyDaysTakeExactlyHalfTheWidth() {
        assertEquals(0f, scale.xOf(now))
        assertTrue(abs(scale.xOf(now + 60.days) - width / 2f) < 0.5f, "60 days landed at ${scale.xOf(now + 60.days)}")
        assertTrue(abs(scale.xOf(end) - width) < 0.5f)
    }

    @Test
    fun theNearTermIsAnOrderOfMagnitudeMoreSpaciousThanTheFarTerm() {
        val nearPixelsPerDay = scale.xOf(now + 30.days) / 30f
        val farPixelsPerDay = (scale.xOf(now + 400.days) - scale.xOf(now + 370.days)) / 30f
        // Half the width for 60 days against half for the remaining ~1035:
        // a ratio of about 17 over a three-year horizon.
        assertTrue(nearPixelsPerDay > farPixelsPerDay * 15, "near=$nearPixelsPerDay far=$farPixelsPerDay")
    }

    @Test
    fun isMonotonicAcrossTheGradientChange() {
        var previous = -1f
        for (day in 0..(365 * 3)) {
            val x = scale.xOf(now + day.days)
            assertTrue(x >= previous, "x went backwards at day $day: $x after $previous")
            previous = x
        }
    }

    @Test
    fun invertsBackToTheSameInstant() {
        for (day in listOf(0, 1, 30, 59, 60, 61, 200, 900, 1095)) {
            val instant = now + day.days
            val roundTripped = scale.instantAt(scale.xOf(instant))
            assertTrue(
                abs((roundTripped - instant).inWholeMinutes) <= 60,
                "day $day round-tripped to $roundTripped, expected $instant",
            )
        }
    }

    @Test
    fun clampsOutsideTheHorizonRatherThanRunningOffTheCanvas() {
        assertEquals(0f, scale.xOf(now - 10.days))
        assertEquals(width, scale.xOf(end + 10.days))
    }

    @Test
    fun aHorizonShorterThanTheNearTermIsPlainlyLinear() {
        val short = TimelineScale(now, now + 10.days, width)
        assertTrue(abs(short.xOf(now + 5.days) - width / 2f) < 0.5f, "expected a linear axis over a 10-day horizon")
        // With nothing beyond the near term there is no gradient change to mark.
        assertEquals(null, short.nearTermBoundaryX)
    }

    @Test
    fun theGradientChangeIsReportedWhereItHappens() {
        assertEquals(width / 2f, scale.nearTermBoundaryX)
    }
}
