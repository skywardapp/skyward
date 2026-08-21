package dev.fritze.skyward.ui.upcoming

import dev.fritze.skyward.core.model.AuroraForecastKind
import dev.fritze.skyward.core.model.AuroraPayload
import dev.fritze.skyward.core.model.Certainty
import dev.fritze.skyward.core.model.GeoPoint
import dev.fritze.skyward.core.model.Occurrence
import dev.fritze.skyward.core.model.Phenomenon
import dev.fritze.skyward.core.model.SavedLocation
import dev.fritze.skyward.core.model.TimeWindow
import dev.fritze.skyward.core.planner.UpcomingFilter
import dev.fritze.skyward.core.planner.UpcomingScope
import dev.fritze.skyward.core.visibility.AuroraVisibilityModel
import dev.fritze.skyward.core.visibility.OvationGrid
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * The time-boundary behaviour of §13.2's Upcoming screen: nothing in the
 * repository, filter or refresh flows fires when a countdown rolls over or a
 * nowcast expires, so the screen has to invalidate itself (UpcomingTicker.kt).
 */
@OptIn(ExperimentalCoroutinesApi::class) // runCurrent/advanceTimeBy
class UpcomingTickerTest {

    private val start = Instant.parse("2026-01-01T18:00:00Z")
    private val home = SavedLocation(
        id = "home",
        name = "Home",
        point = GeoPoint(60.0, 10.0),
        isPrimary = true,
        createdAt = start,
        modifiedAt = start,
    )
    private val visibilityModels = mapOf(Phenomenon.AURORA to AuroraVisibilityModel())

    @Test
    fun countdownTextUpdatesWithoutAnyInputEmission() = runTest {
        // 2 d 30 min out, so the "In 2 days" -> "Tomorrow" rollover is half an
        // hour away rather than exactly now.
        val target = start + 2.days + 30.minutes
        val threeDay = auroraOccurrence(
            "3day",
            window = TimeWindow(target, target + 1.hours),
            peakTime = target,
            expiresAt = target + 2.hours,
            kind = AuroraForecastKind.THREE_DAY,
        )
        val states = collectStates(
            base(listOf(threeDay), filter = UpcomingFilter(scope = UpcomingScope.ALL)),
        )

        runCurrent()
        assertEquals(listOf("In 2 days"), states.map { it.countdown() })

        advanceTimeBy(31.minutes)
        runCurrent()
        assertEquals("Tomorrow", states.last().countdown())
    }

    @Test
    fun auroraBannerDisappearsAtExpiresAt() = runTest {
        val expiresAt = start + 45.minutes
        val nowcast = auroraOccurrence(
            "nowcast",
            window = TimeWindow(start - 15.minutes, start + 4.hours),
            peakTime = start - 15.minutes,
            expiresAt = expiresAt,
        )
        val states = collectStates(base(occurrences = listOf(nowcast)))

        runCurrent()
        assertEquals("nowcast", assertNotNull(states.last().auroraBanner).occurrenceId)

        advanceTimeBy(44.minutes)
        runCurrent()
        assertNotNull(states.last().auroraBanner, "banner must survive right up to expiry")

        advanceTimeBy(1.minutes)
        runCurrent()
        assertNull(states.last().auroraBanner)
        assertEquals(expiresAt, states.last().now)
    }

    @Test
    fun auroraBannerDisappearsAtWindowEndWhenExpiresAtIsAbsent() = runTest {
        val windowEnd = start + 30.minutes
        val nowcast = auroraOccurrence(
            "nowcast",
            window = TimeWindow(start - 15.minutes, windowEnd),
            peakTime = start - 15.minutes,
            expiresAt = null,
        )
        val states = collectStates(base(occurrences = listOf(nowcast)))

        runCurrent()
        assertNotNull(states.last().auroraBanner)

        advanceTimeBy(31.minutes)
        runCurrent()
        assertNull(states.last().auroraBanner)
    }

    @Test
    fun auroraBannerAppearsWhenItsWindowOpens() = runTest {
        val windowStart = start + 20.minutes
        val nowcast = auroraOccurrence(
            "nowcast",
            window = TimeWindow(windowStart, windowStart + 1.hours),
            peakTime = windowStart,
            expiresAt = windowStart + 2.hours,
        )
        val states = collectStates(base(occurrences = listOf(nowcast)))

        runCurrent()
        assertNull(states.last().auroraBanner)

        advanceTimeBy(21.minutes)
        runCurrent()
        assertNotNull(states.last().auroraBanner)
    }

    @Test
    fun tickingStopsWhenNothingIsLeftToInvalidate() = runTest {
        val states = collectStates(base(occurrences = emptyList()))

        runCurrent()
        assertEquals(1, states.size)

        // No boundary exists, so the flow completes rather than polling.
        advanceTimeBy(7.days)
        runCurrent()
        assertEquals(1, states.size)
    }

    @Test
    fun countdownBoundaryNeverLandsAfterTheTextHasAlreadyChanged() {
        val target = Instant.parse("2026-03-01T12:00:00Z")
        val remainders = listOf(
            Duration.ZERO, 1.milliseconds, 1.seconds, 30.minutes, 23.hours, 1.days,
            1.days + 1.seconds, 2.days, 6.days, 7.days, 8.days, 59.days, 60.days,
            61.days, 120.days, 400.days,
        )
        for (remaining in remainders) {
            val now = target - remaining
            val boundary = assertNotNull(countdownChangeAfter(target, now), "remaining=$remaining")
            assertTrue(boundary > now, "boundary must lie in the future (remaining=$remaining)")
            assertEquals(
                countdownText(target, now),
                countdownText(target, boundary - 1.nanoseconds),
                "the text must not have changed before the announced boundary (remaining=$remaining)",
            )
        }
    }

    @Test
    fun countdownBoundaryIsWhereTheWordingActuallyTurnsOver() {
        val target = Instant.parse("2026-03-01T12:00:00Z")
        val expected = mapOf(
            Duration.ZERO to ("Today" to "Past"),
            12.hours to ("Today" to "Past"),
            1.days to ("Tomorrow" to "Today"),
            (1.days + 12.hours) to ("Tomorrow" to "Today"),
            2.days to ("In 2 days" to "Tomorrow"),
            7.days to ("In 1 week" to "In 6 days"),
            14.days to ("In 2 weeks" to "In 1 week"),
            60.days to ("In 2 months" to "In 8 weeks"),
        )
        for ((remaining, texts) in expected) {
            val now = target - remaining
            val boundary = assertNotNull(countdownChangeAfter(target, now))
            assertEquals(texts.first, countdownText(target, now), "at now (remaining=$remaining)")
            assertEquals(texts.second, countdownText(target, boundary), "at boundary (remaining=$remaining)")
        }
    }

    @Test
    fun aPastCountdownHasNoFurtherBoundary() {
        val target = Instant.parse("2026-03-01T12:00:00Z")
        assertNull(countdownChangeAfter(target, target + 1.nanoseconds))
        assertNull(countdownChangeAfter(target, target + 30.days))
    }

    /**
     * #71: an empty list has two causes and one of them is the user's to fix.
     * The screen can only tell them apart if the state carries the answer, and
     * an occurrence in the horizon is not evidence either way — a location can
     * be missing while sources have plenty to say.
     */
    @Test
    fun emptyLocationListIsCarriedIntoTheState() = runTest {
        val target = start + 2.days
        val occurrence = auroraOccurrence(
            "3day",
            window = TimeWindow(target, target + 1.hours),
            peakTime = target,
            expiresAt = target + 2.hours,
            kind = AuroraForecastKind.THREE_DAY,
        )

        val withLocation = collectStates(base(listOf(occurrence), UpcomingFilter(scope = UpcomingScope.ALL)))
        val withoutLocation = collectStates(
            base(listOf(occurrence), UpcomingFilter(scope = UpcomingScope.ALL), locations = emptyList()),
        )
        runCurrent()

        assertTrue(withLocation.last().hasLocations)
        assertFalse(withoutLocation.last().hasLocations)
    }

    /** #71: "Live Kp unavailable" has to be able to say whether the fetch failed. */
    @Test
    fun liveKpFailureIsCarriedIntoTheState() = runTest {
        val target = start + 2.days
        val occurrence = auroraOccurrence(
            "3day",
            window = TimeWindow(target, target + 1.hours),
            peakTime = target,
            expiresAt = target + 2.hours,
            kind = AuroraForecastKind.THREE_DAY,
        )

        val states = collectStates(
            base(listOf(occurrence), UpcomingFilter(scope = UpcomingScope.ALL)),
            liveKpFailed = true,
        )
        runCurrent()

        assertTrue(states.last().liveKpFailed)
    }

    /**
     * Collects into a list that later assertions read; the flow keeps ticking
     * on [TestScope.backgroundScope], which `runTest` cancels for us.
     */
    private fun TestScope.collectStates(
        base: UpcomingBaseState,
        liveKpFailed: Boolean = false,
    ): List<UpcomingUiState> {
        val states = mutableListOf<UpcomingUiState>()
        upcomingStatesOverTime(
            base = base,
            currentKp = null,
            ovationGrid = grid(home.point to 62),
            visibilityModels = visibilityModels,
            clock = virtualClock(),
            liveKpFailed = liveKpFailed,
        ).onEach { states += it }.launchIn(backgroundScope)
        return states
    }

    /** A clock that reads the test scheduler, so `delay` and `now()` agree. */
    private fun TestScope.virtualClock(): Clock = object : Clock {
        override fun now(): Instant = start + testScheduler.currentTime.milliseconds
    }

    private fun UpcomingUiState.countdown(): String {
        val item = items.single()
        return countdownText(countdownAnchor(item.occurrence), now)
    }

    private fun base(
        occurrences: List<Occurrence>,
        filter: UpcomingFilter = UpcomingFilter(),
        locations: List<SavedLocation> = listOf(home),
    ) = UpcomingBaseState(
        occurrences = occurrences,
        locations = locations,
        rules = emptyList(),
        filter = filter,
        isRefreshing = false,
    )

    private fun auroraOccurrence(
        id: String,
        window: TimeWindow,
        peakTime: Instant,
        expiresAt: Instant?,
        kind: AuroraForecastKind = AuroraForecastKind.NOWCAST,
    ) = Occurrence(
        id = id,
        phenomenon = Phenomenon.AURORA,
        sourceId = "swpc",
        title = "Aurora",
        window = window,
        peakTime = peakTime,
        certainty = Certainty.FORECAST,
        payload = AuroraPayload(
            kpForecast = 6.0,
            forecastKind = kind,
            issuedAt = window.start,
        ),
        fetchedAt = window.start,
        expiresAt = expiresAt,
    )

    private fun grid(vararg cells: Pair<GeoPoint, Int>): OvationGrid {
        val bytes = ByteArray(360 * 181)
        for ((point, probability) in cells) {
            val lon = point.lonDeg.toInt().mod(360)
            val lat = point.latDeg.toInt() + 90
            bytes[(lon * 181) + lat] = probability.toByte()
        }
        return OvationGrid(start, start, bytes)
    }
}
