package dev.fritze.skyward.desktop.scheduler

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.fritze.skyward.core.model.Certainty
import dev.fritze.skyward.core.model.GeoPoint
import dev.fritze.skyward.core.model.MoonEventKind
import dev.fritze.skyward.core.model.MoonEventPayload
import dev.fritze.skyward.core.model.NotificationStatus
import dev.fritze.skyward.core.model.Occurrence
import dev.fritze.skyward.core.model.Phenomenon
import dev.fritze.skyward.core.model.PlannedNotification
import dev.fritze.skyward.core.model.Precision
import dev.fritze.skyward.core.model.TimeWindow
import dev.fritze.skyward.core.persistence.NotificationRepo
import dev.fritze.skyward.core.persistence.OccurrenceRepo
import dev.fritze.skyward.core.persistence.SkywardDatabase
import dev.fritze.skyward.desktop.notify.DesktopNotification
import dev.fritze.skyward.desktop.notify.DesktopNotifier
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/**
 * §10.3's in-process scheduler, on `runTest`'s virtual clock throughout.
 *
 * Both repositories are constructed with this test's own
 * [StandardTestDispatcher], so their SQL runs on the scheduler `runTest`
 * controls rather than on `Dispatchers.Default` — without that the writes
 * these assertions are about land on a real thread pool at a real moment,
 * and the only way to wait for one is to sleep and hope. Two of the
 * assertions here are negatives ("it did *not* fire a second time", "a
 * future reminder waits"), and a negative proven by a real sleep only ever
 * proves the machine was busy enough not to get there yet.
 *
 * With the dispatcher injected, `advanceUntilIdle()` means exactly "run
 * everything that is ready" and `advanceTimeBy` means exactly "let this much
 * of the schedule elapse" — no sleeps, no polling, and nothing that gets
 * slower or flakier on a loaded CI runner.
 */
@OptIn(ExperimentalCoroutinesApi::class) // advanceUntilIdle/advanceTimeBy
class DesktopSchedulerTest {

    private val now = Instant.parse("2026-08-14T20:00:00Z")

    private class RecordingNotifier(private val succeed: Boolean = true) : DesktopNotifier {
        val posted = mutableListOf<DesktopNotification>()
        override fun post(notification: DesktopNotification, onActivated: (String?) -> Unit): Boolean {
            posted += notification
            return succeed
        }
    }

    private class FixedClock(var instant: Instant) : Clock {
        override fun now(): Instant = instant
    }

    private fun newDatabase(): SkywardDatabase {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        SkywardDatabase.Schema.create(driver)
        return SkywardDatabase(driver)
    }

    /** Repos whose SQL runs on [scope]'s scheduler, so `advanceUntilIdle()` covers it. */
    private fun TestScope.repos(database: SkywardDatabase = newDatabase()): Pair<NotificationRepo, OccurrenceRepo> {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return NotificationRepo(database, dispatcher) to OccurrenceRepo(database, dispatcher)
    }

    private fun notification(id: String, fireAt: Instant, status: NotificationStatus = NotificationStatus.PENDING) =
        PlannedNotification(
            id = id,
            occurrenceId = "moon:2026-08",
            ruleId = "rule",
            locationId = "home",
            fireAt = fireAt,
            status = status,
            precision = Precision.EXACT,
            title = "Supermoon tonight",
            body = "Rises at 20:41 at Home.",
            createdAt = fireAt - 1.hours,
            firedAt = null,
        )

    private fun occurrence(window: TimeWindow) = Occurrence(
        id = "moon:2026-08",
        phenomenon = Phenomenon.MOON_EVENT,
        sourceId = "moon",
        title = "Supermoon",
        window = window,
        peakTime = window.start,
        certainty = Certainty.CERTAIN,
        payload = MoonEventPayload(MoonEventKind.SUPERMOON, window.start, window.start, 357_000.0),
        fetchedAt = window.start,
        expiresAt = null,
    )

    @Test
    fun firesADueReminderExactlyOnceAndRecordsIt() = runTest {
        val (notificationRepo, occurrenceRepo) = repos()
        val notifier = RecordingNotifier()
        notificationRepo.upsert(notification("due", now))

        val scheduler = DesktopScheduler(notificationRepo, occurrenceRepo, notifier, onActivated = {}, clock = FixedClock(now))
        val job = launch { scheduler.run() }
        // Runs the whole loop to quiescence — including the re-emission the
        // scheduler's own FIRED write triggers, which is where a second,
        // wrong delivery would come from. Nothing is left in flight when
        // this returns, so the count below is final rather than merely
        // current.
        advanceUntilIdle()
        job.cancel()

        assertEquals(1, notifier.posted.size, "expected exactly one delivery, got ${notifier.posted}")
        assertEquals("Supermoon tonight", notifier.posted.single().title)
        val stored = notificationRepo.getById("due")!!
        assertEquals(NotificationStatus.FIRED, stored.status)
        assertTrue(stored.firedAt != null, "a fired reminder must record when it fired")
    }

    @Test
    fun doesNotFireSomethingStillInTheFuture() = runTest {
        val (notificationRepo, occurrenceRepo) = repos()
        val notifier = RecordingNotifier()
        notificationRepo.upsert(notification("later", now + 6.hours))

        val scheduler = DesktopScheduler(notificationRepo, occurrenceRepo, notifier, onActivated = {}, clock = FixedClock(now))
        val job: Job = launch { scheduler.run() }
        // Five of the six hours until it is due. `advanceUntilIdle()` would
        // not do here: the clock is fixed, so the loop re-arms its `delay`
        // forever and idle never arrives. Advancing a stated amount says
        // what the assertion means — "with an hour still to go, nothing has
        // fired" — where the old `delay(300)` said only "not within 300 ms
        // of real time on this machine".
        advanceTimeBy(5.hours)
        job.cancel()

        assertTrue(notifier.posted.isEmpty(), "a future reminder must wait, got ${notifier.posted}")
        assertEquals(NotificationStatus.PENDING, notificationRepo.getById("later")?.status)
    }

    @Test
    fun aDeliveryFailureStillClosesTheReminderOut() = runTest {
        val (notificationRepo, occurrenceRepo) = repos()
        val notifier = RecordingNotifier(succeed = false)
        notificationRepo.upsert(notification("undeliverable", now))

        val scheduler = DesktopScheduler(notificationRepo, occurrenceRepo, notifier, onActivated = {}, clock = FixedClock(now))
        val job = launch { scheduler.run() }
        advanceUntilIdle()
        job.cancel()

        // Reposting on every subsequent DB change would be worse than losing
        // one reminder the desktop refused to show.
        assertEquals(1, notifier.posted.size, "expected one attempt, got ${notifier.posted.size}")
        assertEquals(NotificationStatus.FIRED, notificationRepo.getById("undeliverable")?.status)
    }

    @Test
    fun startupReportsWhatWasMissedInsteadOfFiringIt() = runTest {
        val (notificationRepo, occurrenceRepo) = repos()
        val notifier = RecordingNotifier()

        val stale = notification("stale", now - 3.hours)
        notificationRepo.upsert(stale)
        occurrenceRepo.upsert(occurrence(TimeWindow(now - 4.hours, now - 2.hours)), firstSeenAt = now - 5.hours)

        val scheduler = DesktopScheduler(notificationRepo, occurrenceRepo, notifier, onActivated = {}, clock = FixedClock(now))
        val missed = scheduler.collectMissedWhileAway(now, preexistingIds = setOf("stale"))

        assertEquals(1, missed.size)
        assertEquals("stale", missed.single().notification.id)
        assertEquals("Supermoon", missed.single().occurrence?.title)
        assertEquals(NotificationStatus.MISSED, notificationRepo.getById("stale")?.status)
        assertTrue(notifier.posted.isEmpty(), "§10.3: missed reminders are listed, never fired late")
    }

    @Test
    fun aReminderDiscoveredDuringStartupIsNotDemotedIntoTheMissedPanel() = runTest {
        val (notificationRepo, occurrenceRepo) = repos()
        val notifier = RecordingNotifier()

        // A notifyOnFirstSeen row the startup re-plan just created: its fireAt
        // is `now`, so only the preexisting-id check separates it from
        // something the user actually missed.
        notificationRepo.upsert(notification("fresh-nowcast", now))

        val scheduler = DesktopScheduler(notificationRepo, occurrenceRepo, notifier, onActivated = {}, clock = FixedClock(now))
        val missed = scheduler.collectMissedWhileAway(now, preexistingIds = emptySet())

        assertTrue(missed.isEmpty(), "a just-discovered reminder is news, not history")
        assertEquals(NotificationStatus.PENDING, notificationRepo.getById("fresh-nowcast")?.status)
    }

    @Test
    fun alreadyFiredHistoryIsLeftAlone() = runTest {
        val (notificationRepo, occurrenceRepo) = repos()
        notificationRepo.upsert(notification("history", now - 5.hours, NotificationStatus.FIRED))

        val scheduler = DesktopScheduler(notificationRepo, occurrenceRepo, RecordingNotifier(), onActivated = {}, clock = FixedClock(now))
        val missed = scheduler.collectMissedWhileAway(now, preexistingIds = setOf("history"))

        assertTrue(missed.isEmpty())
        assertEquals(NotificationStatus.FIRED, notificationRepo.getById("history")?.status)
    }
}
