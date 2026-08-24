package dev.fritze.skyward.core.chart

import dev.fritze.skyward.core.format.formatTime
import dev.fritze.skyward.core.model.GeoPoint
import dev.fritze.skyward.core.model.SavedLocation
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/**
 * §14.3's slider spans "the selected night". Which night that is has to be
 * decided from a value that does not move with the clock, or the slider
 * resets under the user's hand every minute.
 */
class NightAnchorTest {

    private val berlin = TimeZone.of("Europe/Berlin") // UTC+2 in August

    @Test
    fun anEveningLooksAtTheNightThatIsAboutToStart() {
        // 21:00 local on the 14th → noon on the 14th.
        assertEquals(
            Instant.parse("2026-08-14T10:00:00Z"),
            nightAnchor(Instant.parse("2026-08-14T19:00:00Z"), berlin),
        )
    }

    @Test
    fun aLateNightLooksAtTheNightInProgressNotTheNextOne() {
        // 01:00 local on the 15th is still "the night of the 14th"; anchoring
        // to the 15th's noon would show tomorrow's sky to someone standing
        // outside right now.
        assertEquals(
            Instant.parse("2026-08-14T10:00:00Z"),
            nightAnchor(Instant.parse("2026-08-14T23:00:00Z"), berlin),
        )
    }

    @Test
    fun theAnchorIsStableAcrossAWholeNight() {
        // The whole point: every tick from noon to the following noon has to
        // produce the same value, or `remember(night)` rebuilds the window.
        val evening = nightAnchor(Instant.parse("2026-08-14T18:00:00Z"), berlin)
        val midnight = nightAnchor(Instant.parse("2026-08-14T22:00:00Z"), berlin)
        val smallHours = nightAnchor(Instant.parse("2026-08-15T03:00:00Z"), berlin)
        val morning = nightAnchor(Instant.parse("2026-08-15T08:00:00Z"), berlin)

        assertEquals(evening, midnight)
        assertEquals(evening, smallHours)
        assertEquals(evening, morning)
    }

    @Test
    fun theAnchorMovesOnAtLocalNoon() {
        val before = nightAnchor(Instant.parse("2026-08-15T09:59:00Z"), berlin) // 11:59 local
        val after = nightAnchor(Instant.parse("2026-08-15T10:01:00Z"), berlin) // 12:01 local
        assertEquals(Instant.parse("2026-08-14T10:00:00Z"), before)
        assertEquals(Instant.parse("2026-08-15T10:00:00Z"), after)
    }

    /**
     * The fallback window is a pair of civil times — 18:00 to 06:00 — not a
     * pair of offsets from noon. A night containing a DST transition is 23 or
     * 25 hours long, so `anchor + 18.hours` would label the window 05:00 or
     * 07:00.
     *
     * The two halves have to coincide for this to be reachable at all, and
     * they do: the fallback runs only where there is no astronomical darkness,
     * which in late March starts somewhere between 71° and 75°, and Svalbard
     * is at 78° on Oslo time. Tromsø, at 69.65°, still has a real window on
     * this date and would have tested nothing.
     */
    @Test
    fun theFallbackWindowKeepsItsCivilHoursAcrossADstTransition() {
        val longyearbyen = SavedLocation(
            id = "longyearbyen",
            name = "Longyearbyen",
            point = GeoPoint(78.22, 15.65),
            isPrimary = true,
            createdAt = Instant.parse("2026-01-01T00:00:00Z"),
            modifiedAt = Instant.parse("2026-01-01T00:00:00Z"),
        )
        val oslo = TimeZone.of("Europe/Oslo")
        // Clocks go forward 02:00 → 03:00 on Sunday 29 March 2026, so this
        // night is 23 hours long.
        val anchor = nightAnchor(Instant.parse("2026-03-28T20:00:00Z"), oslo)
        val night = nightWindow(longyearbyen, anchor, oslo)

        assertFalse(
            night.isAstronomicalDarkness,
            "Svalbard has no astronomical darkness in late March; without this " +
                "the test would not reach the fallback branch at all",
        )
        assertEquals("18:00", formatTime(night.start, oslo))
        assertEquals("06:00", formatTime(night.end, oslo))
        // Twelve civil hours, eleven elapsed: the hour the transition removed.
        assertEquals(11.hours, night.end - night.start)
    }

    @Test
    fun theZoneIsTheUsersNotUtc() {
        // 23:00 UTC is already 08:00 the next morning in Tokyo, which is
        // *before* noon there — so Tokyo is still on the previous night.
        val tokyo = TimeZone.of("Asia/Tokyo")
        assertEquals(
            Instant.parse("2026-08-14T03:00:00Z"), // noon on the 14th, JST
            nightAnchor(Instant.parse("2026-08-14T23:00:00Z"), tokyo),
        )
    }
}
