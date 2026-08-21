package dev.fritze.skyward.alarm

import android.Manifest
import android.os.Build
import android.os.SystemClock
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import dev.fritze.skyward.SkywardApplication
import dev.fritze.skyward.core.model.NotificationStatus
import dev.fritze.skyward.core.model.PlannedNotification
import dev.fritze.skyward.core.model.Precision
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    fun resetGateAndClearNotifications() {
        // Reset rather than assume: the card tests substitute a gate on this
        // same process-singleton container, and a stale fake would quietly
        // turn every posting assertion below into a no-op.
        container.notificationGate = AndroidNotificationGate(context)
        // Both cancelAll() and notify() are one-way calls into system_server:
        // they return before the shade has caught up. Waiting for the cancel to
        // land keeps the *previous* test's teardown from arriving after this
        // test has already posted, and taking its notification with it.
        NotificationManagerCompat.from(context).cancelAll()
        // Asserted, not merely awaited: on a timeout the helper returns whatever
        // is still up, and starting a test with a leftover notification would
        // show as a confusing failure in the test body instead of here.
        val remaining = awaitNotifications { it.isEmpty() }
        assertTrue("notifications did not clear before the test started", remaining.isEmpty())
    }

    @After
    fun restoreNotificationGate() {
        container.notificationGate = AndroidNotificationGate(context)
    }

    /**
     * Polls `activeNotifications` until [predicate] holds, or the deadline
     * passes. Reading it once, immediately after `notify()`, is a race that
     * usually wins — which is the worst kind: it made this suite fail
     * intermittently on CI rather than never.
     */
    private fun awaitNotifications(
        timeoutMillis: Long = NOTIFICATION_TIMEOUT_MILLIS,
        predicate: (List<StatusBarNotification>) -> Boolean,
    ): List<StatusBarNotification> {
        val deadline = SystemClock.uptimeMillis() + timeoutMillis
        var active = NotificationManagerCompat.from(context).activeNotifications
        while (!predicate(active) && SystemClock.uptimeMillis() < deadline) {
            Thread.sleep(NOTIFICATION_POLL_MILLIS)
            active = NotificationManagerCompat.from(context).activeNotifications
        }
        return active
    }

    private fun awaitPosted(notificationId: String): StatusBarNotification? =
        awaitNotifications { list -> list.any { it.id == notificationId.hashCode() } }
            .firstOrNull { it.id == notificationId.hashCode() }

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

        assertTrue("expected notification ${n.id.hashCode()} to be posted", awaitPosted(n.id) != null)
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

        val posted = awaitPosted(n.id)
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

    /**
     * §10.1's honesty contract: "never silently dropped". A reminder the OS
     * refuses to show must not be written into history as a delivery that
     * happened — issue #52's failure mode, where declining the onboarding
     * permission prompt made every future reminder vanish while the row
     * claimed FIRED.
     */
    @Test
    fun blockedNotificationsRecordMissedAndPostNothing() = runTest {
        container.notificationGate = NotificationGate { false }
        val n = freshNotification(Clock.System.now(), NotificationStatus.REGISTERED, Precision.EXACT)
        container.notificationRepo.upsert(n)

        NotificationPoster.postNotificationFor(context, container, n.id)

        val stored = container.notificationRepo.getById(n.id)
        assertEquals(NotificationStatus.MISSED, stored?.status)
        assertNull("a reminder nobody saw must not carry a fired timestamp", stored?.firedAt)
        assertTrue("expected nothing to be posted while notifications are blocked", awaitNoPost(n.id))
    }

    /** MISSED is terminal (§10.4), so a duplicate alarm must not resurrect it. */
    @Test
    fun missedRowIsNotPostedByALateDuplicateAlarm() = runTest {
        val n = freshNotification(Clock.System.now(), NotificationStatus.MISSED, Precision.EXACT)
        container.notificationRepo.upsert(n)

        NotificationPoster.postNotificationFor(context, container, n.id)

        assertEquals(NotificationStatus.MISSED, container.notificationRepo.getById(n.id)?.status)
        assertTrue("a MISSED row must stay missed", awaitNoPost(n.id))
    }

    /**
     * §10.5's approximate hedge appends its "enable exact alarms" sentence on
     * the first APPROXIMATE notification *ever*. Spending that one chance on a
     * notification the OS then refused to show would silently lose it, so the
     * blocked check has to come before the body is rendered.
     */
    @Test
    fun blockedNotificationDoesNotConsumeTheOnceEverApproximateHedge() = runTest {
        container.notificationGate = NotificationGate { false }
        container.settingsRepo.delete(APPROXIMATE_HEDGE_SHOWN_KEY)
        val n = freshNotification(Clock.System.now(), NotificationStatus.REGISTERED, Precision.APPROXIMATE)
        container.notificationRepo.upsert(n)

        NotificationPoster.postNotificationFor(context, container, n.id)

        assertNull("the hedge must still be owed to the user", container.settingsRepo.get(APPROXIMATE_HEDGE_SHOWN_KEY))
    }

    /** True if [notificationId] is still absent from the shade after a short grace period. */
    private fun awaitNoPost(notificationId: String): Boolean =
        awaitNotifications(timeoutMillis = ABSENCE_GRACE_MILLIS) { list -> list.any { it.id == notificationId.hashCode() } }
            .none { it.id == notificationId.hashCode() }

    private companion object {
        const val NOTIFICATION_TIMEOUT_MILLIS = 5_000L
        const val NOTIFICATION_POLL_MILLIS = 50L

        // Proving a *negative* can only ever be "still nothing after a while";
        // the full timeout would just be dead time on every such assertion.
        const val ABSENCE_GRACE_MILLIS = 500L
        const val APPROXIMATE_HEDGE_SHOWN_KEY = "approximate_hedge_shown"
    }
}
