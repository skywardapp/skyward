package dev.fritze.skyward.core.sources

import dev.fritze.skyward.core.model.AuroraForecastKind
import dev.fritze.skyward.core.model.AuroraPayload
import dev.fritze.skyward.core.model.Certainty
import dev.fritze.skyward.core.model.CometElements
import dev.fritze.skyward.core.model.CometMagParams
import dev.fritze.skyward.core.model.CometPayload
import dev.fritze.skyward.core.model.GeoPoint
import dev.fritze.skyward.core.model.Occurrence
import dev.fritze.skyward.core.model.Phenomenon
import dev.fritze.skyward.core.model.SolarEclipseKind
import dev.fritze.skyward.core.model.SolarEclipsePayload
import dev.fritze.skyward.core.model.TimeWindow
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** §6.3 point 3: the exact list is a test case, not a comment. */
class MaterialChangeTest {

    private val now = Instant.parse("2026-01-01T00:00:00Z")

    private fun eclipseOcc(peakTime: Instant, kind: SolarEclipseKind = SolarEclipseKind.TOTAL) = Occurrence(
        id = "se:test", phenomenon = Phenomenon.SOLAR_ECLIPSE, sourceId = "eclipse", title = "t",
        window = TimeWindow(peakTime - 1.hours, peakTime + 1.hours), peakTime = peakTime, certainty = Certainty.CERTAIN,
        payload = SolarEclipsePayload(kind, GeoPoint(0.0, 0.0), peakTime, emptyList(), 1.0),
        fetchedAt = now, expiresAt = null,
    )

    private fun auroraOcc(kp: Double) = Occurrence(
        id = "au:test", phenomenon = Phenomenon.AURORA, sourceId = "swpc", title = "t",
        window = TimeWindow(now, now + 3.hours), peakTime = null, certainty = Certainty.FORECAST,
        payload = AuroraPayload(kpForecast = kp, forecastKind = AuroraForecastKind.THREE_DAY, issuedAt = now),
        fetchedAt = now, expiresAt = null,
    )

    @Test
    fun peakTimeMovingUnderFiveMinutesIsNotMaterial() {
        assertFalse(isMaterialChange(eclipseOcc(now), eclipseOcc(now + 4.minutes)))
    }

    @Test
    fun peakTimeMovingOverFiveMinutesIsMaterial() {
        assertTrue(isMaterialChange(eclipseOcc(now), eclipseOcc(now + 6.minutes)))
    }

    @Test
    fun eclipseKindChangingIsMaterial() {
        assertTrue(isMaterialChange(eclipseOcc(now, SolarEclipseKind.PARTIAL), eclipseOcc(now, SolarEclipseKind.TOTAL)))
    }

    @Test
    fun kpChangingByAtLeastHalfIsMaterial() {
        assertFalse(isMaterialChange(auroraOcc(4.0), auroraOcc(4.4)))
        assertTrue(isMaterialChange(auroraOcc(4.0), auroraOcc(4.5)))
    }

    @Test
    fun magAtIngestChangingAloneIsNotMaterial() {
        val elements = CometElements(now, 0.9, 1.0, 0.0, 0.0, 0.0, now)
        fun cometOcc(magAtIngest: Double) = Occurrence(
            id = "cm:test", phenomenon = Phenomenon.COMET, sourceId = "jpl", title = "t",
            window = TimeWindow(now, now + 1.days), peakTime = now, certainty = Certainty.FORECAST,
            payload = CometPayload("C/test", null, elements, CometMagParams(4.0, 4.5), now, 4.0, now, magAtIngest = magAtIngest),
            fetchedAt = now, expiresAt = null,
        )
        assertFalse(isMaterialChange(cometOcc(4.0), cometOcc(3.5)), "magAtIngest is display-only by design (§7.4.3)")
    }

    @Test
    fun cometElementsChangingIsMaterial() {
        val elements = CometElements(now, 0.9, 1.0, 0.0, 0.0, 0.0, now)
        val refinedElements = elements.copy(inclinationDeg = 0.01)
        fun cometOcc(el: CometElements) = Occurrence(
            id = "cm:test", phenomenon = Phenomenon.COMET, sourceId = "jpl", title = "t",
            window = TimeWindow(now, now + 1.days), peakTime = now, certainty = Certainty.FORECAST,
            payload = CometPayload("C/test", null, el, CometMagParams(4.0, 4.5), now, 4.0, now, magAtIngest = 4.0),
            fetchedAt = now, expiresAt = null,
        )
        assertTrue(
            isMaterialChange(cometOcc(elements), cometOcc(refinedElements)),
            "an SBDB orbit refinement can shift bestViewingStart even with peakMagDate unchanged (issue #106)",
        )
        assertFalse(isMaterialChange(cometOcc(elements), cometOcc(elements.copy())))
    }

    @Test
    fun cometMagParamsChangingAloneIsNotMaterial() {
        val elements = CometElements(now, 0.9, 1.0, 0.0, 0.0, 0.0, now)
        fun cometOcc(mp: CometMagParams) = Occurrence(
            id = "cm:test", phenomenon = Phenomenon.COMET, sourceId = "jpl", title = "t",
            window = TimeWindow(now, now + 1.days), peakTime = now, certainty = Certainty.FORECAST,
            payload = CometPayload("C/test", null, elements, mp, now, 4.0, now, magAtIngest = 4.0),
            fetchedAt = now, expiresAt = null,
        )
        assertFalse(isMaterialChange(cometOcc(CometMagParams(4.0, 4.5)), cometOcc(CometMagParams(5.0, 6.0))))
    }

    @Test
    fun fetchedAtChangingAloneIsNotMaterial() {
        val a = eclipseOcc(now)
        val b = a.copy(fetchedAt = now + 1.days)
        assertFalse(isMaterialChange(a, b))
    }

    @Test
    fun peakTimeAppearingOrDisappearingIsMaterialButBothNullIsNot() {
        val withPeak = auroraOcc(4.0).copy(peakTime = now)
        val withoutPeak = auroraOcc(4.0)
        assertTrue(isMaterialChange(withoutPeak, withPeak))
        assertTrue(isMaterialChange(withPeak, withoutPeak))
        assertFalse(isMaterialChange(withoutPeak, withoutPeak))
    }
}
