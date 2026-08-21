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
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.delay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * §10.3's in-process scheduler. Uses `runBlocking` rather than `runTest`
 * deliberately: the repositories dispatch their SQL onto `Dispatchers.Default`,
 * so a virtual-time test scheduler would not actually wait for the writes
 * these assertions are about.
 */
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
    fun firesADueReminderExactlyOnceAndRecordsIt() = runBlocking {
        val database = newDatabase()
        val notificationRepo = NotificationRepo(database)
        val notifier = RecordingNotifier()
        notificationRepo.upsert(notification("due", now))

        val scheduler = DesktopScheduler(notificationRepo, OccurrenceRepo(database), notifier, onActivated = {}, clock = FixedClock(now))
        val job = launch { scheduler.run() }
        awaitCondition { notificationRepo.getById("due")?.status == NotificationStatus.FIRED }
        // Give the loop a chance to (wrongly) fire it a second time off the
        // re-emission its own write triggers.
        delay(200)
        job.cancel()

        assertEquals(1, notifier.posted.size, "expected exactly one delivery, got ${notifier.posted}")
        assertEquals("Supermoon tonight", notifier.posted.single().title)
        val stored = notificationRepo.getById("due")!!
        assertEquals(NotificationStatus.FIRED, stored.status)
        assertTrue(stored.firedAt != null, "a fired reminder must record when it fired")
    }

    @Test
    fun doesNotFireSomethingStillInTheFuture() = runBlocking {
        val database = newDatabase()
        val notificationRepo = NotificationRepo(database)
        val notifier = RecordingNotifier()
        notificationRepo.upsert(notification("later", now + 6.hours))

        val scheduler = DesktopScheduler(notificationRepo, OccurrenceRepo(database), notifier, onActivated = {}, clock = FixedClock(now))
        val job: Job = launch { scheduler.run() }
        delay(300)
        job.cancel()

        assertTrue(notifier.posted.isEmpty(), "a future reminder must wait, got ${notifier.posted}")
        assertEquals(NotificationStatus.PENDING, notificationRepo.getById("later")?.status)
    }

    @Test
    fun aDeliveryFailureStillClosesTheReminderOut() = runBlocking {
        val database = newDatabase()
        val notificationRepo = NotificationRepo(database)
        val notifier = RecordingNotifier(succeed = false)
        notificationRepo.upsert(notification("undeliverable", now))

        val scheduler = DesktopScheduler(notificationRepo, OccurrenceRepo(database), notifier, onActivated = {}, clock = FixedClock(now))
        val job = launch { scheduler.run() }
        awaitCondition { notificationRepo.getById("undeliverable")?.status == NotificationStatus.FIRED }
        delay(200)
        job.cancel()

        // Reposting on every subsequent DB change would be worse than losing
        // one reminder the desktop refused to show.
        assertEquals(1, notifier.posted.size, "expected one attempt, got ${notifier.posted.size}")
    }

    /**
     * #79: closing the reminder out is right; doing it silently is not. The
     * outcome has to reach something the user can see, which on desktop means
     * the window — stderr is not a notification surface.
     */
    @Test
    fun aDeliveryFailureIsReportedToTheWindow() = runBlocking {
        val database = newDatabase()
        val notificationRepo = NotificationRepo(database)
        val outcomes = mutableListOf<Boolean>()
        notificationRepo.upsert(notification("undeliverable", now))

        val scheduler = DesktopScheduler(
            notificationRepo,
            OccurrenceRepo(database),
            RecordingNotifier(succeed = false),
            onActivated = {},
            onDeliveryOutcome = { outcomes += it },
            clock = FixedClock(now),
        )
        val job = launch { scheduler.run() }
        awaitCondition { outcomes.isNotEmpty() }
        job.cancel()

        assertEquals(listOf(false), outcomes)
    }

    /** The success half: a delivery that worked retracts a standing warning. */
    @Test
    fun aSuccessfulDeliveryIsReportedTooSoTheWarningCanBeRetracted() = runBlocking {
        val database = newDatabase()
        val notificationRepo = NotificationRepo(database)
        val outcomes = mutableListOf<Boolean>()
        notificationRepo.upsert(notification("deliverable", now))

        val scheduler = DesktopScheduler(
            notificationRepo,
            OccurrenceRepo(database),
            RecordingNotifier(),
            onActivated = {},
            onDeliveryOutcome = { outcomes += it },
            clock = FixedClock(now),
        )
        val job = launch { scheduler.run() }
        awaitCondition { outcomes.isNotEmpty() }
        job.cancel()

        assertEquals(listOf(true), outcomes)
    }

    @Test
    fun startupReportsWhatWasMissedInsteadOfFiringIt() = runBlocking {
        val database = newDatabase()
        val notificationRepo = NotificationRepo(database)
        val occurrenceRepo = OccurrenceRepo(database)
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
    fun aReminderDiscoveredDuringStartupIsNotDemotedIntoTheMissedPanel() = runBlocking {
        val database = newDatabase()
        val notificationRepo = NotificationRepo(database)
        val notifier = RecordingNotifier()

        // A notifyOnFirstSeen row the startup re-plan just created: its fireAt
        // is `now`, so only the preexisting-id check separates it from
        // something the user actually missed.
        notificationRepo.upsert(notification("fresh-nowcast", now))

        val scheduler = DesktopScheduler(notificationRepo, OccurrenceRepo(database), notifier, onActivated = {}, clock = FixedClock(now))
        val missed = scheduler.collectMissedWhileAway(now, preexistingIds = emptySet())

        assertTrue(missed.isEmpty(), "a just-discovered reminder is news, not history")
        assertEquals(NotificationStatus.PENDING, notificationRepo.getById("fresh-nowcast")?.status)
    }

    @Test
    fun alreadyFiredHistoryIsLeftAlone() = runBlocking {
        val database = newDatabase()
        val notificationRepo = NotificationRepo(database)
        notificationRepo.upsert(notification("history", now - 5.hours, NotificationStatus.FIRED))

        val scheduler = DesktopScheduler(notificationRepo, OccurrenceRepo(database), RecordingNotifier(), onActivated = {}, clock = FixedClock(now))
        val missed = scheduler.collectMissedWhileAway(now, preexistingIds = setOf("history"))

        assertTrue(missed.isEmpty())
        assertEquals(NotificationStatus.FIRED, notificationRepo.getById("history")?.status)
    }

    /** Polls a real (not virtual) clock, since the writes under test happen on other dispatchers. */
    private suspend fun awaitCondition(timeout: kotlin.time.Duration = 5.minutes / 60, block: suspend () -> Boolean) {
        val satisfied = withTimeoutOrNull(timeout) {
            while (!block()) delay(10)
            true
        }
        assertTrue(satisfied == true, "condition not met within $timeout")
    }
}
