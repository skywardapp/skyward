package dev.fritze.skyward.alarm

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.fritze.skyward.core.model.NotificationStatus
import dev.fritze.skyward.core.model.PlannedNotification
import dev.fritze.skyward.core.model.Precision
import dev.fritze.skyward.core.persistence.NotificationRepo
import dev.fritze.skyward.core.persistence.SkywardDatabase
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
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

    private fun repo(): NotificationRepo {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        SkywardDatabase.Schema.create(driver)
        return NotificationRepo(SkywardDatabase(driver))
    }

    private fun notification(id: String, fireAt: Instant, status: NotificationStatus, precision: Precision = Precision.EXACT) = PlannedNotification(
        id = id, occurrenceId = "occ", ruleId = "rule", locationId = "loc", fireAt = fireAt, status = status,
        precision = precision, title = "t", body = "b", createdAt = now, firedAt = null,
    )

    @Test
    fun pendingWithinTheWindowIsScheduledAndPromotedToRegistered() = runTest {
        val repository = repo()
        val n = notification("n1", now + 1.days, NotificationStatus.PENDING)
        repository.upsert(n)
        val scheduler = FakeAlarmScheduler(exact = true)

        AlarmSyncer.sync(listOf(n), scheduler, repository, now)

        assertEquals(n.id, scheduler.scheduled[n.id]?.id)
        assertEquals(NotificationStatus.REGISTERED, repository.getById(n.id)?.status)
        assertEquals(Precision.EXACT, repository.getById(n.id)?.precision)
    }

    @Test
    fun pendingBeyondTheFourteenDayWindowIsNotScheduled() = runTest {
        val repository = repo()
        val n = notification("n1", now + 20.days, NotificationStatus.PENDING)
        repository.upsert(n)
        val scheduler = FakeAlarmScheduler()

        AlarmSyncer.sync(listOf(n), scheduler, repository, now)

        assertEquals(0, scheduler.scheduled.size)
        assertEquals(NotificationStatus.PENDING, repository.getById(n.id)?.status, "still pending -- the daily top-up job will pick it up once it's in range")
    }

    @Test
    fun approximatePathRecordsThePrecisionActuallyAchieved() = runTest {
        val repository = repo()
        val n = notification("n1", now + 1.hours, NotificationStatus.PENDING)
        repository.upsert(n)
        val scheduler = FakeAlarmScheduler(exact = false)

        AlarmSyncer.sync(listOf(n), scheduler, repository, now)

        assertEquals(Precision.APPROXIMATE, repository.getById(n.id)?.precision)
        assertEquals(NotificationStatus.REGISTERED, repository.getById(n.id)?.status)
    }

    @Test
    fun cancelledAndMissedRowsAreCancelledOnTheScheduler() = runTest {
        val repository = repo()
        val cancelledN = notification("c1", now + 1.days, NotificationStatus.CANCELLED)
        val missedN = notification("m1", now - 1.hours, NotificationStatus.MISSED)
        repository.upsert(cancelledN)
        repository.upsert(missedN)
        val scheduler = FakeAlarmScheduler()

        AlarmSyncer.sync(listOf(cancelledN, missedN), scheduler, repository, now)

        assertEquals(setOf("c1", "m1"), scheduler.cancelled)
    }

    @Test
    fun aRegisteredRowWhoseFireAtDriftedOutsideTheWindowIsDemotedBackToPending() = runTest {
        val repository = repo()
        // Registered previously (e.g. was within 14 days at last sync); now
        // its fireAt has moved out past the window on this reconcile pass.
        val n = notification("n1", now + 20.days, NotificationStatus.REGISTERED)
        repository.upsert(n)
        val scheduler = FakeAlarmScheduler()

        AlarmSyncer.sync(listOf(n), scheduler, repository, now)

        assertEquals(NotificationStatus.PENDING, repository.getById(n.id)?.status)
        assertEquals(setOf("n1"), scheduler.cancelled)
    }

    @Test
    fun pastDuePendingOrRegisteredRowIsMarkedMissedInsteadOfFiringImmediately() = runTest {
        val repository = repo()
        val pending = notification("n1", now - 1.hours, NotificationStatus.PENDING)
        val registered = notification("n2", now - 2.hours, NotificationStatus.REGISTERED)
        repository.upsert(pending)
        repository.upsert(registered)
        val scheduler = FakeAlarmScheduler()

        AlarmSyncer.sync(listOf(pending, registered), scheduler, repository, now)

        assertEquals(0, scheduler.scheduled.size)
        assertEquals(setOf("n1", "n2"), scheduler.cancelled)
        assertEquals(NotificationStatus.MISSED, repository.getById(pending.id)?.status)
        assertEquals(NotificationStatus.MISSED, repository.getById(registered.id)?.status)
    }

    @Test
    fun firedRowsAreLeftAlone() = runTest {
        val repository = repo()
        val n = notification("n1", now - 1.hours, NotificationStatus.FIRED)
        repository.upsert(n)
        val scheduler = FakeAlarmScheduler()

        AlarmSyncer.sync(listOf(n), scheduler, repository, now)

        assertEquals(0, scheduler.scheduled.size)
        assertEquals(0, scheduler.cancelled.size)
        assertEquals(NotificationStatus.FIRED, repository.getById(n.id)?.status)
    }
}
