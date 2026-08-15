package dev.fritze.skyward.desktop.ui.skychart

import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
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
