package dev.fritze.skyward.core.chart

import dev.fritze.skyward.core.model.GeoPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * §14.4 Row 3's verdict, which both dashboards now print from one place.
 *
 * §8.4 inverted: visible when `|λgm| >= 66 - 2*Kp`, so the threshold is
 * `Kp_needed = (66 - |λgm|)/2`.
 */
class AuroraVerdictTest {

    private val tromso = GeoPoint(69.65, 18.96)
    private val berlin = GeoPoint(52.52, 13.40)

    @Test
    fun aHighLatitudeNeedsLessKpThanAMidLatitude() {
        val north = auroraVerdict(tromso, currentKp = null)
        val mid = auroraVerdict(berlin, currentKp = null)
        assertTrue(
            north.kpNeeded < mid.kpNeeded,
            "Tromsø (${north.kpNeeded}) should need less Kp than Berlin (${mid.kpNeeded})",
        )
    }

    @Test
    fun theMarginIsTheReadingLessTheThreshold() {
        val verdict = auroraVerdict(berlin, currentKp = 7.0)
        assertEquals(7.0 - verdict.kpNeeded, verdict.marginKp)
    }

    @Test
    fun aReadingBelowTheThresholdIsNotVisibleNow() {
        assertTrue(!auroraVerdict(berlin, currentKp = 1.0).isVisibleNow)
        assertTrue(auroraVerdict(berlin, currentKp = 9.0).isVisibleNow)
    }

    @Test
    fun insideTheAuroralOvalNoKpIsNeededAtAll() {
        // Far enough north that the §8.4 threshold goes non-positive.
        val verdict = auroraVerdict(GeoPoint(85.0, 0.0), currentKp = null)
        assertTrue(verdict.kpNeeded <= 0, "expected a non-positive threshold, got ${verdict.kpNeeded}")
        assertEquals("Above the auroral boundary at any Kp.", auroraNowSentence(verdict, peakForecastKp = null))
        // And the threshold line agrees rather than printing "Kp ≥ -7.5".
        assertEquals(
            "Geomagnetic latitude 81.0° — inside the auroral oval, visible at any Kp",
            auroraThresholdSentence(verdict),
        )
    }

    @Test
    fun withNoReadingAndNoStoredForecastTheSentenceSaysSoRatherThanShowingZero() {
        val verdict = auroraVerdict(berlin, currentKp = null)
        assertEquals(
            "No current Kp reading and no forecast slot above your thresholds.",
            auroraNowSentence(verdict, peakForecastKp = null),
        )
    }

    @Test
    fun withNoReadingButAStoredForecastItFallsBackToThePeak() {
        val verdict = auroraVerdict(berlin, currentKp = null)
        assertTrue("Forecast peak Kp" in auroraNowSentence(verdict, peakForecastKp = 6.0))
    }

    @Test
    fun aShortfallIsStatedAsAShortfallNotAsMargin() {
        val verdict = auroraVerdict(berlin, currentKp = 2.0)
        val sentence = auroraNowSentence(verdict, peakForecastKp = null)
        assertTrue("short by" in sentence, sentence)
    }

    @Test
    fun aClearedThresholdNamesTheHorizonToWatch() {
        val verdict = auroraVerdict(berlin, currentKp = 9.0)
        val sentence = auroraNowSentence(verdict, peakForecastKp = null)
        assertTrue("of margin" in sentence && "Look " in sentence, sentence)
    }

    @Test
    fun theGaugeNeedleSpansHalfATurnFromKpZeroToNine() {
        val left = kpGaugeAngleRadians(0.0)
        val right = kpGaugeAngleRadians(9.0)
        assertTrue(right - left > 3.13 && right - left < 3.15, "expected ~π of sweep, got ${right - left}")
        // Out-of-range readings clamp to the dial rather than running off it.
        assertEquals(left, kpGaugeAngleRadians(-2.0))
        assertEquals(right, kpGaugeAngleRadians(12.0))
    }

    @Test
    fun theGraticuleRimSitsAtTheOuterEdgeAndHigherLatitudesFallInside() {
        val radius = 100f
        assertEquals(radius, polarRingRadius(AuroraPolarPlot.RIM_LATITUDE, radius))
        assertTrue(polarRingRadius(75.0, radius) < polarRingRadius(60.0, radius))
        assertEquals(0f, polarRingRadius(90.0, radius), "the pole is the centre")
    }
}
