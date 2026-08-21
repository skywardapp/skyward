package dev.fritze.skyward.core.format

import dev.fritze.skyward.core.model.CometElements
import dev.fritze.skyward.core.model.CometMagParams
import dev.fritze.skyward.core.model.CometPayload
import dev.fritze.skyward.core.model.LocalDetails
import dev.fritze.skyward.core.model.Quality
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * §17.4: the presentation edge both frontends share. The point of these is
 * less the exact wording than the two properties issue #53 was filed about —
 * nothing user-facing renders a raw enum or a raw `Instant`, and a countdown
 * on screen is never left stale.
 */
class PresentationTest {

    private val zone = TimeZone.UTC
    private val now = Instant.parse("2026-08-21T12:00:00Z")

    @Test
    fun qualityAndCertaintyRenderAsProseNotEnumConstants() {
        for (quality in Quality.entries) {
            val label = qualityLabel(quality)
            assertTrue(label != quality.name, "raw enum leaked for $quality")
            assertTrue(label.first().isUpperCase() && label.drop(1).none { it.isUpperCase() }, "not sentence case: $label")
        }
        assertEquals("Excellent", qualityLabel(Quality.EXCELLENT))
    }

    @Test
    fun timesRenderAsLocalDatesNotIsoInstants() {
        assertEquals("21 Aug 2026, 12:00", formatDateTime(now, zone))
        assertEquals("12:00", formatTime(now, zone))
        assertEquals("21 Aug", formatDayAndMonth(now, zone))
    }

    @Test
    fun relativeCountsBothDirections() {
        assertEquals("now", formatRelative(now, now + 30.seconds))
        assertEquals("in 25 min", formatRelative(now, now + 25.minutes))
        assertEquals("in 4 h", formatRelative(now, now + 4.hours))
        assertEquals("in 3 days", formatRelative(now, now + 3.days))
        assertEquals("in 1 day", formatRelative(now, now + 1.days))
        assertEquals("3 days ago", formatRelative(now, now - 3.days))
    }

    /**
     * The property `UpcomingTicker`'s `countdownChangeAfter` has, applied to
     * the finer label: a caller that sleeps until the returned instant is
     * never showing text that already went stale.
     */
    @Test
    fun relativeLabelIsStableUntilTheBoundaryItReports() {
        val offsets = listOf(
            (-40).days, (-3).days, (-5).hours, (-90).minutes, (-30).seconds,
            10.seconds, 59.seconds, 1.minutes, 25.minutes, 59.minutes,
            1.hours, 4.hours, 23.hours, 1.days, 3.days, 45.days, 200.days,
        )
        for (offset in offsets) {
            val target = now + offset
            val boundary = relativeChangeAfter(now, target)
            assertTrue(boundary > now, "boundary $boundary is not in the future for offset $offset")
            assertEquals(
                formatRelative(now, target),
                formatRelative(boundary - 1.milliseconds, target),
                "label changed before the reported boundary for offset $offset",
            )
        }
    }

    /** The bound has to actually advance: a boundary that reports "no change ever" would freeze the label. */
    @Test
    fun relativeLabelEventuallyChangesAtTheReportedBoundary() {
        val changing = listOf(30.seconds, 25.minutes, 4.hours, 23.hours, 3.days, (-30).seconds, (-90).minutes, (-5).hours)
        for (offset in changing) {
            val target = now + offset
            val boundary = relativeChangeAfter(now, target)
            assertTrue(
                formatRelative(boundary, target) != formatRelative(now, target),
                "label did not change at the reported boundary for offset $offset",
            )
        }
    }

    @Test
    fun localDetailLinesFormatTimesAndAngles() {
        val lines = localDetailLines(
            LocalDetails.SolarEclipseLocal(
                partialBegin = now - 1.hours,
                peak = now,
                partialEnd = now + 1.hours,
                maxObscuration = 0.873,
                sunAltAtPeakDeg = 41.4,
                localKind = null,
            ),
            zone,
        )

        assertEquals(listOf("Max 87 % obscuration at 12:00, sun 41° up"), lines)
    }

    @Test
    fun auroraLinesRoundKpAndNameTheDarknessGap() {
        val lines = localDetailLines(
            LocalDetails.AuroraLocal(
                geomagneticLatDeg = 57.234,
                kpNeeded = 5.6666,
                ovationProbability = 62,
                darknessStart = null,
                darknessEnd = null,
            ),
            zone,
        )

        assertEquals(
            listOf(
                "Geomagnetic latitude 57.2° — needs Kp 5.7",
                "OVATION overhead probability 62 %",
                "No astronomical darkness tonight",
            ),
            lines,
        )
    }

    /** §7.4.4: the magnitude reads as predicted, the elements are dated, and the caveat is present. */
    @Test
    fun cometBlockDatesItsElementsAndLabelsTheMagnitudeAsPredicted() {
        val payload = cometPayload()

        assertEquals(
            "Predicted magnitude 4.2 (best 3.8 around 14 Apr 2027)",
            cometMagnitudeLine(payload, cometLocal(predictedMag = 4.23), zone),
        )
        assertEquals(
            "From JPL orbital elements of 1 Feb 2027.",
            cometElementsLine(payload, cometLocal(predictedMag = 4.23)),
        )
        assertTrue(COMET_DEVIATION_CAVEAT.contains("deviate"))
    }

    /** With no per-location evaluation yet, the block still has to say something true. */
    @Test
    fun cometBlockFallsBackToThePayloadWhenThereIsNoLocalDetail() {
        assertEquals(
            "Predicted magnitude 3.8 (best 3.8 around 14 Apr 2027)",
            cometMagnitudeLine(cometPayload(), details = null, zone = zone),
        )
        assertEquals("From JPL orbital elements of 1 Feb 2027.", cometElementsLine(cometPayload(), details = null))
    }

    private fun cometPayload() = CometPayload(
        designation = "C/2026 K1",
        name = "Testolino",
        elements = CometElements(
            epoch = Instant.parse("2027-02-01T00:00:00Z"),
            perihelionDistanceAu = 0.53,
            eccentricity = 1.0002,
            inclinationDeg = 116.3,
            ascendingNodeDeg = 21.6,
            argPerihelionDeg = 308.5,
            tpPerihelion = Instant.parse("2027-04-10T00:00:00Z"),
        ),
        magParams = CometMagParams(m1 = 5.0, k1 = 4.0),
        perihelionDate = Instant.parse("2027-04-10T00:00:00Z"),
        peakMag = 3.81,
        peakMagDate = Instant.parse("2027-04-14T21:00:00Z"),
        magAtIngest = 9.4,
    )

    private fun cometLocal(predictedMag: Double) = LocalDetails.CometLocal(
        predictedMag = predictedMag,
        elementEpoch = Instant.parse("2027-02-01T00:00:00Z"),
        maxAltDeg = 27.4,
        maxAltTime = now + 6.hours,
        bestViewingStart = null,
        bestViewingEnd = null,
    )
}
