package dev.fritze.skyward.alarm

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.fritze.skyward.core.model.Certainty
import dev.fritze.skyward.core.model.GeoPoint
import dev.fritze.skyward.core.model.NotificationStatus
import dev.fritze.skyward.core.model.Occurrence
import dev.fritze.skyward.core.model.Phenomenon
import dev.fritze.skyward.core.model.PlannedNotification
import dev.fritze.skyward.core.model.Precision
import dev.fritze.skyward.core.model.SolarEclipseKind
import dev.fritze.skyward.core.model.SolarEclipsePayload
import dev.fritze.skyward.core.model.TimeWindow
import dev.fritze.skyward.core.persistence.NotificationRepo
import dev.fritze.skyward.core.persistence.OccurrenceRepo
import dev.fritze.skyward.core.persistence.SkywardDatabase
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** §10.2: syncing reconciled DB rows onto (fake) OS alarms. */
class AlarmSyncerTest {

    private val now = Instant.parse("2026-01-01T00:00:00Z")

    private class FakeAlarmScheduler(private val exact: Boolean = true) : AlarmScheduler {
        val scheduled = mutableMapOf<String, PlannedNotification>()
        val cancelled = mutableSetOf<String>()

        override fun canScheduleExact() = exact
        override fun schedule(n: PlannedNotification): Precision {
            scheduled[n.id] = n
            cancelled -= n.id
            return if (exact) Precision.EXACT else Precision.APPROXIMATE
        }

        override fun cancel(id: String) {
            cancelled += id
            scheduled -= id
        }
    }

    /**
     * Notification + occurrence repos over one in-memory DB: §10.4's catch-up
     * rule is a question about the *occurrence's* window, so the syncer needs
     * both tables to answer it.
     */
    private class Repos(db: SkywardDatabase) {
        val notifications = NotificationRepo(db)
        val occurrences = OccurrenceRepo(db)
    }

    private fun repos(): Repos {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        SkywardDatabase.Schema.create(driver)
        return Repos(SkywardDatabase(driver))
    }

    /** An occurrence whose window ends at [windowEnd], to sit behind the notifications under test. */
    private fun occurrence(windowEnd: Instant) = Occurrence(
        id = "occ", phenomenon = Phenomenon.SOLAR_ECLIPSE, sourceId = "eclipse", title = "Eclipse",
        window = TimeWindow(windowEnd - 6.hours, windowEnd), peakTime = windowEnd - 3.hours,
        certainty = Certainty.CERTAIN,
        payload = SolarEclipsePayload(SolarEclipseKind.TOTAL, GeoPoint(0.0, 0.0), windowEnd - 3.hours, emptyList(), 1.0),
        fetchedAt = now, expiresAt = null,
    )

    private fun notification(id: String, fireAt: Instant, status: NotificationStatus, precision: Precision = Precision.EXACT) = PlannedNotification(
        id = id, occurrenceId = "occ", ruleId = "rule", locationId = "loc", fireAt = fireAt, status = status,
        precision = precision, title = "t", body = "b", createdAt = now, firedAt = null,
    )

    @Test
    fun pendingWithinTheWindowIsScheduledAndPromotedToRegistered() = runTest {
        val repository = repos()
        val n = notification("n1", now + 1.days, NotificationStatus.PENDING)
        repository.notifications.upsert(n)
        val scheduler = FakeAlarmScheduler(exact = true)

        AlarmSyncer.sync(listOf(n), scheduler, repository.notifications, repository.occurrences, now)

        assertEquals(n.id, scheduler.scheduled[n.id]?.id)
        assertEquals(NotificationStatus.REGISTERED, repository.notifications.getById(n.id)?.status)
        assertEquals(Precision.EXACT, repository.notifications.getById(n.id)?.precision)
    }

    @Test
    fun pendingBeyondTheFourteenDayWindowIsNotScheduled() = runTest {
        val repository = repos()
        val n = notification("n1", now + 20.days, NotificationStatus.PENDING)
        repository.notifications.upsert(n)
        val scheduler = FakeAlarmScheduler()

        AlarmSyncer.sync(listOf(n), scheduler, repository.notifications, repository.occurrences, now)

        assertEquals(0, scheduler.scheduled.size)
        assertEquals(NotificationStatus.PENDING, repository.notifications.getById(n.id)?.status, "still pending -- the daily top-up job will pick it up once it's in range")
    }

    @Test
    fun approximatePathRecordsThePrecisionActuallyAchieved() = runTest {
        val repository = repos()
        val n = notification("n1", now + 1.hours, NotificationStatus.PENDING)
        repository.notifications.upsert(n)
        val scheduler = FakeAlarmScheduler(exact = false)

        AlarmSyncer.sync(listOf(n), scheduler, repository.notifications, repository.occurrences, now)

        assertEquals(Precision.APPROXIMATE, repository.notifications.getById(n.id)?.precision)
        assertEquals(NotificationStatus.REGISTERED, repository.notifications.getById(n.id)?.status)
    }

    @Test
    fun cancelledAndMissedRowsAreCancelledOnTheScheduler() = runTest {
        val repository = repos()
        val cancelledN = notification("c1", now + 1.days, NotificationStatus.CANCELLED)
        val missedN = notification("m1", now - 1.hours, NotificationStatus.MISSED)
        repository.notifications.upsert(cancelledN)
        repository.notifications.upsert(missedN)
        val scheduler = FakeAlarmScheduler()

        AlarmSyncer.sync(listOf(cancelledN, missedN), scheduler, repository.notifications, repository.occurrences, now)

        assertEquals(setOf("c1", "m1"), scheduler.cancelled)
    }

    @Test
    fun aRegisteredRowWhoseFireAtDriftedOutsideTheWindowIsDemotedBackToPending() = runTest {
        val repository = repos()
        // Registered previously (e.g. was within 14 days at last sync); now
        // its fireAt has moved out past the window on this reconcile pass.
        val n = notification("n1", now + 20.days, NotificationStatus.REGISTERED)
        repository.notifications.upsert(n)
        val scheduler = FakeAlarmScheduler()

        AlarmSyncer.sync(listOf(n), scheduler, repository.notifications, repository.occurrences, now)

        assertEquals(NotificationStatus.PENDING, repository.notifications.getById(n.id)?.status)
        assertEquals(setOf("n1"), scheduler.cancelled)
    }

    @Test
    fun pastDueRowStillInsideTheOccurrenceWindowIsRegisteredSoItFiresImmediately() = runTest {
        // §10.4's device-off catch-up, the boot-recovery half: the eclipse is at
        // 01:00 and the "2 h before" reminder came due at 23:00 while the phone
        // was off. It is overdue, not stale -- registering it with its past
        // fireAt is what makes AlarmManager deliver it at once.
        val repository = repos()
        repository.occurrences.upsert(occurrence(windowEnd = now + 2.hours), firstSeenAt = now)
        val pending = notification("n1", now - 1.hours, NotificationStatus.PENDING)
        val registered = notification("n2", now - 2.hours, NotificationStatus.REGISTERED)
        repository.notifications.upsert(pending)
        repository.notifications.upsert(registered)
        val scheduler = FakeAlarmScheduler()

        AlarmSyncer.sync(listOf(pending, registered), scheduler, repository.notifications, repository.occurrences, now)

        assertEquals(setOf("n1", "n2"), scheduler.scheduled.keys)
        assertEquals(pending.fireAt, scheduler.scheduled["n1"]?.fireAt, "registered at the time it was due, not rewritten to `now`")
        assertEquals(0, scheduler.cancelled.size)
        assertEquals(NotificationStatus.REGISTERED, repository.notifications.getById(pending.id)?.status)
        assertEquals(NotificationStatus.REGISTERED, repository.notifications.getById(registered.id)?.status)
    }

    @Test
    fun pastDueRowWhoseOccurrenceWindowHasClosedIsMarkedMissed() = runTest {
        val repository = repos()
        repository.occurrences.upsert(occurrence(windowEnd = now - 30.minutes), firstSeenAt = now - 1.days)
        val pending = notification("n1", now - 1.hours, NotificationStatus.PENDING)
        repository.notifications.upsert(pending)
        val scheduler = FakeAlarmScheduler()

        AlarmSyncer.sync(listOf(pending), scheduler, repository.notifications, repository.occurrences, now)

        assertEquals(0, scheduler.scheduled.size)
        assertEquals(setOf("n1"), scheduler.cancelled)
        assertEquals(NotificationStatus.MISSED, repository.notifications.getById(pending.id)?.status)
    }

    @Test
    fun pastDueRowWhoseOccurrenceHasBeenWithdrawnIsMarkedMissed() = runTest {
        // No occurrence row at all (§6.3 withdrew it while the device was off):
        // there is no window left to still be inside, so the reminder is history.
        val repository = repos()
        val pending = notification("n1", now - 1.hours, NotificationStatus.PENDING)
        repository.notifications.upsert(pending)
        val scheduler = FakeAlarmScheduler()

        AlarmSyncer.sync(listOf(pending), scheduler, repository.notifications, repository.occurrences, now)

        assertEquals(setOf("n1"), scheduler.cancelled)
        assertEquals(NotificationStatus.MISSED, repository.notifications.getById(pending.id)?.status)
    }

    @Test
    fun firedRowsAreLeftAlone() = runTest {
        val repository = repos()
        val n = notification("n1", now - 1.hours, NotificationStatus.FIRED)
        repository.notifications.upsert(n)
        val scheduler = FakeAlarmScheduler()

        AlarmSyncer.sync(listOf(n), scheduler, repository.notifications, repository.occurrences, now)

        assertEquals(0, scheduler.scheduled.size)
        assertEquals(0, scheduler.cancelled.size)
        assertEquals(NotificationStatus.FIRED, repository.notifications.getById(n.id)?.status)
    }
}
