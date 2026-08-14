package dev.fritze.skyward.alarm

import android.Manifest
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import dev.fritze.skyward.SkywardApplication
import dev.fritze.skyward.core.model.NotificationStatus
import dev.fritze.skyward.core.model.PlannedNotification
import dev.fritze.skyward.core.model.Precision
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import java.util.UUID
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * §17.5 Android instrumented smoke tests: real DB, real [NotificationPoster]/
 * [AlarmSyncer], a [FakeAlarmScheduler] standing in for `AlarmManager`/
 * `WorkManager` (§10.2's own test seam). SAF export/import round-trip is
 * excluded per M3's own accept criteria (ships in M5).
 *
 * Drives [NotificationPoster.postNotificationFor] and [AlarmSyncer.sync]
 * directly rather than firing a real `BroadcastReceiver` through
 * `goAsync()`, which the system, not a test process, is meant to drive.
 */
@RunWith(AndroidJUnit4::class)
class AlarmFlowInstrumentedTest {

    // POST_NOTIFICATIONS only exists from API 33; below that it is implicitly granted and
    // asking UiAutomation to grant it fails outright ("SecurityException: Error granting
    // runtime permission"), so the rule has to be a no-op on older emulators rather than an
    // unconditional grant.
    @get:Rule
    val permissionRule: TestRule =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            TestRule { base, _ -> base }
        }

    private val context = ApplicationProvider.getApplicationContext<SkywardApplication>()
    private val container = context.container

    @Before
    fun clearNotifications() {
        NotificationManagerCompat.from(context).cancelAll()
    }

    private fun freshNotification(fireAt: Instant, status: NotificationStatus, precision: Precision) = PlannedNotification(
        id = "test-${UUID.randomUUID()}",
        occurrenceId = "occ-${UUID.randomUUID()}",
        ruleId = "rule-${UUID.randomUUID()}",
        locationId = "loc-${UUID.randomUUID()}",
        fireAt = fireAt,
        status = status,
        precision = precision,
        title = "Perseids peak tonight",
        body = "Peaks around 02:30 local time, best after midnight.",
        createdAt = Clock.System.now(),
        firedAt = null,
    )

    @Test
    fun exactAlarmRegistersAndReceiverPostsNotification() = runTest {
        container.alarmScheduler = FakeAlarmScheduler(canScheduleExact = true)
        val n = freshNotification(Clock.System.now() + 5.minutes, NotificationStatus.PENDING, Precision.EXACT)
        container.notificationRepo.upsert(n)

        val scheduler = container.alarmScheduler as FakeAlarmScheduler
        AlarmSyncer.sync(listOf(n), scheduler, container.notificationRepo, Clock.System.now())
        assertEquals(listOf(n.id), scheduler.scheduled.map { it.id })
        assertEquals(Precision.EXACT, container.notificationRepo.getById(n.id)?.precision)
        assertEquals(NotificationStatus.REGISTERED, container.notificationRepo.getById(n.id)?.status)

        // Simulate what NotificationAlarmReceiver does once AlarmManager fires it.
        NotificationPoster.postNotificationFor(context, container, n.id)

        val active = NotificationManagerCompat.from(context).activeNotifications
        assertTrue("expected notification ${n.id.hashCode()} to be posted", active.any { it.id == n.id.hashCode() })
        assertEquals(NotificationStatus.FIRED, container.notificationRepo.getById(n.id)?.status)
    }

    @Test
    fun exactAlarmUnavailableFallsBackToApproximateWithHedgedCopy() = runTest {
        container.alarmScheduler = FakeAlarmScheduler(canScheduleExact = false)
        val scheduler = container.alarmScheduler as FakeAlarmScheduler
        val n = freshNotification(Clock.System.now() + 5.minutes, NotificationStatus.PENDING, Precision.EXACT)
        container.notificationRepo.upsert(n)

        AlarmSyncer.sync(listOf(n), scheduler, container.notificationRepo, Clock.System.now())
        assertEquals(listOf(n.id), scheduler.scheduled.map { it.id })
        assertEquals(Precision.APPROXIMATE, container.notificationRepo.getById(n.id)?.precision)

        NotificationPoster.postNotificationFor(context, container, n.id)

        val active = NotificationManagerCompat.from(context).activeNotifications
        val posted = active.firstOrNull { it.id == n.id.hashCode() }
        assertTrue("expected approximate notification to be posted", posted != null)
        val text = posted!!.notification.extras.getCharSequence(android.app.Notification.EXTRA_TEXT).toString()
        assertTrue("expected hedged copy to mention 'around'", text.contains("around"))
    }

    @Test
    fun permissionGrantedMidLifePromotesRowsBackToExact() = runTest {
        val scheduler = FakeAlarmScheduler(canScheduleExact = false)
        container.alarmScheduler = scheduler
        val n = freshNotification(Clock.System.now() + 10.minutes, NotificationStatus.PENDING, Precision.EXACT)
        container.notificationRepo.upsert(n)

        AlarmSyncer.sync(listOf(n), scheduler, container.notificationRepo, Clock.System.now())
        assertEquals(Precision.APPROXIMATE, container.notificationRepo.getById(n.id)?.precision)

        // §10.2: ExactAlarmPermissionReceiver re-syncs once the permission flips.
        scheduler.setCanScheduleExact(true)
        val registered = container.notificationRepo.getById(n.id)!!
        AlarmSyncer.sync(listOf(registered), scheduler, container.notificationRepo, Clock.System.now())

        assertEquals(Precision.EXACT, container.notificationRepo.getById(n.id)?.precision)
    }

    @Test
    fun bootReceiverReSyncsRegisteredRowsOntoFreshAlarms() = runTest {
        val scheduler = FakeAlarmScheduler(canScheduleExact = true)
        container.alarmScheduler = scheduler
        val n = freshNotification(Clock.System.now() + 1.minutes, NotificationStatus.REGISTERED, Precision.EXACT)
        container.notificationRepo.upsert(n)

        // Mirrors BootReceiver.onReceive: every REGISTERED row is idempotently
        // re-registered, since a reboot wipes AlarmManager's alarms but not the DB.
        val all = container.notificationRepo.getAll()
        AlarmSyncer.sync(all, scheduler, container.notificationRepo, Clock.System.now())

        assertTrue("expected boot re-sync to re-register the row", scheduler.scheduled.any { it.id == n.id })
    }
}
