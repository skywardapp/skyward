package dev.fritze.skyward.core.astro

import io.github.cosinekitty.astronomy.Aberration
import io.github.cosinekitty.astronomy.Body
import io.github.cosinekitty.astronomy.EquatorEpoch
import io.github.cosinekitty.astronomy.Observer
import io.github.cosinekitty.astronomy.Refraction
import io.github.cosinekitty.astronomy.Time
import io.github.cosinekitty.astronomy.equator
import io.github.cosinekitty.astronomy.horizon
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Validated against the sunrise-sunset.org JSON API (fetched live
 * 2026-08-13, `https://api.sunrise-sunset.org/json?lat=48.2&lng=11.6&date=2026-08-13&formatted=0`):
 * `astronomical_twilight_end` (sun descending through -18 deg, i.e. dusk) =
 * 2026-08-13T20:39:52Z; `astronomical_twilight_begin` (ascending through
 * -18 deg the following morning, i.e. dawn) = 2026-08-13T01:57:06Z (that
 * field is reported for the calendar day, so it's the *previous* dawn —
 * the dawn following this dusk is the same time one sidereal day later,
 * i.e. 2026-08-14T01:57ish; checked to ~2 min tolerance below since a full
 * day's drift at 48N in August is on the order of a minute).
 */
class AltAzTest {

    private val munich = Observer(48.2, 11.6, 0.0)

    @Test
    fun darknessWindowMatchesPublishedAstronomicalTwilightForMunich() {
        val noonThatDay = Instant.parse("2026-08-13T12:00:00Z").toAstroTime()
        val window = darknessWindow(noonThatDay, munich, altitudeDeg = -18.0)
        assertNotNull(window, "expected an astronomical-night window to exist at 48N in August")

        val expectedDusk = Instant.parse("2026-08-13T20:39:52Z")
        val expectedDawn = Instant.parse("2026-08-14T01:57:06Z")

        val duskDeltaMin = abs((window.start.toInstant() - expectedDusk).inWholeMinutes)
        val dawnDeltaMin = abs((window.end.toInstant() - expectedDawn).inWholeMinutes)
        assertTrue(duskDeltaMin <= 3, "dusk off by $duskDeltaMin min: got ${window.start.toInstant()}")
        assertTrue(dawnDeltaMin <= 3, "dawn off by $dawnDeltaMin min: got ${window.end.toInstant()}")
    }

    @Test
    fun sunAltitudeAtFoundDuskAndDawnMatchesTheRequestedThreshold() {
        // searchAltitude (and therefore darknessWindow) targets the
        // non-refracted altitude by convention (SearchContext_Altitude uses
        // Refraction.None — twilight altitudes are below the horizon, where
        // refraction "is not directly observable" per the vendored engine's
        // own doc comment), so verify against that, not altitudeDeg()'s
        // Refraction.Normal (which differs by several tenths of a degree
        // even well below the horizon).
        val noonThatDay = Instant.parse("2026-08-13T12:00:00Z").toAstroTime()
        val window = darknessWindow(noonThatDay, munich, altitudeDeg = -12.0)
        assertNotNull(window)
        assertTrue(abs(unrefractedSunAltitudeDeg(window.start, munich) - -12.0) < 0.01)
        assertTrue(abs(unrefractedSunAltitudeDeg(window.end, munich) - -12.0) < 0.01)
    }

    private fun unrefractedSunAltitudeDeg(time: Time, observer: Observer): Double {
        val equ = equator(Body.Sun, time, observer, EquatorEpoch.OfDate, Aberration.Corrected)
        return horizon(time, observer, equ.ra, equ.dec, Refraction.None).altitude
    }

    @Test
    fun aWiderDarknessThresholdProducesAWiderWindow() {
        val noonThatDay = Instant.parse("2026-08-13T12:00:00Z").toAstroTime()
        val nautical = darknessWindow(noonThatDay, munich, altitudeDeg = -12.0)
        val astronomical = darknessWindow(noonThatDay, munich, altitudeDeg = -18.0)
        assertNotNull(nautical)
        assertNotNull(astronomical)
        // -12deg darkness starts earlier in the evening and ends later in the
        // morning than the stricter -18deg "fully dark" window.
        assertTrue(nautical.start.tt < astronomical.start.tt)
        assertTrue(nautical.end.tt > astronomical.end.tt)
    }

    @Test
    fun returnsTheUpcomingNightWhenQueriedDuringDaylight() {
        // Noon is broad daylight; the returned window must be entirely after it.
        val noon = Instant.parse("2026-08-13T12:00:00Z").toAstroTime()
        val window = darknessWindow(noon, munich)
        assertNotNull(window)
        assertTrue(window.start.tt > noon.tt)
    }

    @Test
    fun returnsTheContainingNightWhenQueriedDuringDarkness() {
        val middleOfTheNight = Instant.parse("2026-08-13T23:30:00Z").toAstroTime() // ~01:30 CEST, well after dusk
        val window = darknessWindow(middleOfTheNight, munich)
        assertNotNull(window)
        assertTrue(window.start.tt <= middleOfTheNight.tt && middleOfTheNight.tt <= window.end.tt)
    }

    @Test
    fun returnsNullNearThePoleInPolarDaySummer() {
        // Above the Arctic Circle in June: the sun never gets anywhere near
        // -12 deg for weeks, so no night exists within a short search window.
        val svalbard = Observer(78.2, 15.6, 0.0)
        val midsummer = Instant.parse("2026-06-21T12:00:00Z").toAstroTime()
        val window = darknessWindow(midsummer, svalbard, altitudeDeg = -12.0, searchLimitDays = 2.0)
        assertTrue(window == null, "expected no astronomical night at 78N in midsummer within a 2-day search")
    }

    @Test
    fun subPointIsWhereTheBodyIsAtTheZenith() {
        // Self-consistency: by definition, altitude at the computed sub-point
        // must be ~90deg. Tolerance is loose (not 89.99) because subPoint()
        // uses an observer at the geocenter's surface projection rather than
        // a true geocentric vector — up to ~1deg of lunar parallax error is
        // expected and acceptable for this trip-planning-only guidance.
        for (isoTime in listOf("2026-08-13T00:00:00Z", "2026-08-13T06:00:00Z", "2026-08-13T12:00:00Z", "2026-08-13T18:00:00Z")) {
            val t = Instant.parse(isoTime).toAstroTime()
            val point = subPoint(Body.Moon, t)
            val observer = Observer(point.latDeg, point.lonDeg, 0.0)
            val alt = altitudeDeg(Body.Moon, t, observer)
            assertTrue(alt > 88.5, "expected ~90deg at the sub-point for $isoTime, got $alt (point=$point)")
        }
    }

    @Test
    fun visibleFractionIsOneWhenUpTheWholeWindowAndZeroWhenDownTheWholeWindow() {
        val window = darknessWindow(Instant.parse("2026-08-13T12:00:00Z").toAstroTime(), munich)!!
        // Sun is below the horizon by definition throughout its own darkness window.
        assertEquals(0.0, visibleFraction(Body.Sun, window.start, window.end, munich), 0.0)
    }

    @Test
    fun visibleFractionInterpolatesWhenTheBodyRisesOrSetsMidWindow() {
        // Sun sets partway through a window straddling dusk.
        val dusk = darknessWindow(Instant.parse("2026-08-13T12:00:00Z").toAstroTime(), munich, altitudeDeg = 0.0)!!.start
        val windowStart = dusk.addDays(-0.02) // ~29 min before sunset
        val windowEnd = dusk.addDays(0.02) // ~29 min after
        val fraction = visibleFraction(Body.Sun, windowStart, windowEnd, munich)
        assertTrue(fraction in 0.3..0.7, "expected sunset roughly in the middle of this window, got $fraction")
    }
}
