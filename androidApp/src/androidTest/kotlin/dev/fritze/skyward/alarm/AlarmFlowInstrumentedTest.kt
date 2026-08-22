package dev.fritze.skyward.alarm

import android.Manifest
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import dev.fritze.skyward.SkywardApplication
import dev.fritze.skyward.core.model.NotificationStatus
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
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * §17.5 Android instrumented smoke tests: real DB, real [NotificationPoster]/
 * [AlarmSyncer], a [FakeAlarmScheduler] standing in for `AlarmManager`/
 * `WorkManager` (§10.2's own test seam).
 *
 * This suite deliberately stops at the domain boundary: it drives
 * [NotificationPoster.postNotificationFor] and [AlarmSyncer.sync] directly
 * instead of the receivers and workers that call them in production. That
 * used to be the whole of §17.5's coverage, which meant the plumbing between
 * those pieces was asserted by nobody (#55). It no longer is:
 *
 * - [RealAlarmSchedulingTest] runs [AndroidAlarmScheduler] against the real
 *   `AlarmManager` and `WorkManager`, and lets the system dispatch to the real
 *   [NotificationAlarmReceiver];
 * - [SystemBroadcastReceiverTest] does the same for [BootReceiver] and
 *   [ExactAlarmPermissionReceiver];
 * - [WorkerExecutionTest] runs the three workers through the real
 *   [SkywardWorkerFactory];
 * - [dev.fritze.skyward.ui.settings.SyncRoundTripTest] covers §17.5's SAF
 *   export/import round-trip, which M3's accept criteria deferred to M5.
 *
 * What keeps *these* tests worth having is the states the OS will not produce
 * on demand: notifications blocked at the app level, the once-ever APPROXIMATE
 * hedge still unspent, and #48's pair of boot catch-up cases -- each of which
 * needs a scheduler that answers as told rather than as the device happens to
 * be configured. See ADR 0018.
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
        container.restoreRealNotificationGate(context)
        // Asserted, not merely awaited: on a timeout clearShade() returns
        // whatever is still up, and starting a test with a leftover
        // notification would show as a confusing failure in the test body
        // instead of here.
        assertTrue("notifications did not clear before the test started", context.clearShade().isEmpty())
    }

    @After
    fun restoreTheSeamsThisSuitePins() {
        container.restoreRealNotificationGate(context)
        // The scheduler is pinned per test above and owes the same restore for
        // the same reason: AppContainer lives for the whole instrumentation
        // process, so a FakeAlarmScheduler left behind would silently swallow
        // the alarm registration of whichever suite the runner picks next.
        container.alarmScheduler = AndroidAlarmScheduler(context)
    }

    @Test
    fun exactAlarmRegistersAndReceiverPostsNotification() = runTest {
        container.alarmScheduler = FakeAlarmScheduler(canScheduleExact = true)
        val n = freshNotification(Clock.System.now() + 5.minutes, NotificationStatus.PENDING, Precision.EXACT)
        container.notificationRepo.upsert(n)

        val scheduler = container.alarmScheduler as FakeAlarmScheduler
        AlarmSyncer.sync(listOf(n), scheduler, container.notificationRepo, container.occurrenceRepo, Clock.System.now())
        assertEquals(listOf(n.id), scheduler.scheduled.map { it.id })
        assertEquals(Precision.EXACT, container.notificationRepo.getById(n.id)?.precision)
        assertEquals(NotificationStatus.REGISTERED, container.notificationRepo.getById(n.id)?.status)

        // Simulate what NotificationAlarmReceiver does once AlarmManager fires it.
        NotificationPoster.postNotificationFor(context, container, n.id)

        assertTrue("expected notification ${n.id.hashCode()} to be posted", context.awaitPosted(n.id) != null)
        assertEquals(NotificationStatus.FIRED, container.notificationRepo.getById(n.id)?.status)
    }

    /**
     * #51: a posted reminder has to name what a tap opens. Without a
     * contentIntent the notification is inert -- it opens nothing, and
     * `setAutoCancel(true)`, which only fires through a content intent,
     * cannot even dismiss it. Where the intent then routes is
     * [NotificationTapTest]'s job; this asserts the notification carries one
     * at all, on the real Builder output the shade receives.
     */
    @Test
    fun firedNotificationCarriesATapTarget() = runTest {
        container.alarmScheduler = FakeAlarmScheduler(canScheduleExact = true)
        val n = freshNotification(Clock.System.now() + 5.minutes, NotificationStatus.PENDING, Precision.EXACT)
        container.notificationRepo.upsert(n)

        NotificationPoster.postNotificationFor(context, container, n.id)

        val posted = context.awaitPosted(n.id)
        assertTrue("expected notification to be posted", posted != null)
        val contentIntent = posted!!.notification.contentIntent
        assertTrue("expected a contentIntent so the tap opens the app", contentIntent != null)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            assertTrue("expected the tap to start an Activity", contentIntent!!.isActivity)
        }
    }

    @Test
    fun exactAlarmUnavailableFallsBackToApproximateWithHedgedCopy() = runTest {
        container.alarmScheduler = FakeAlarmScheduler(canScheduleExact = false)
        val scheduler = container.alarmScheduler as FakeAlarmScheduler
        val n = freshNotification(Clock.System.now() + 5.minutes, NotificationStatus.PENDING, Precision.EXACT)
        container.notificationRepo.upsert(n)

        AlarmSyncer.sync(listOf(n), scheduler, container.notificationRepo, container.occurrenceRepo, Clock.System.now())
        assertEquals(listOf(n.id), scheduler.scheduled.map { it.id })
        assertEquals(Precision.APPROXIMATE, container.notificationRepo.getById(n.id)?.precision)

        NotificationPoster.postNotificationFor(context, container, n.id)

        val posted = context.awaitPosted(n.id)
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

        AlarmSyncer.sync(listOf(n), scheduler, container.notificationRepo, container.occurrenceRepo, Clock.System.now())
        assertEquals(Precision.APPROXIMATE, container.notificationRepo.getById(n.id)?.precision)

        // §10.2: ExactAlarmPermissionReceiver re-syncs once the permission flips.
        scheduler.setCanScheduleExact(true)
        val registered = container.notificationRepo.getById(n.id)!!
        AlarmSyncer.sync(listOf(registered), scheduler, container.notificationRepo, container.occurrenceRepo, Clock.System.now())

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
        AlarmSyncer.sync(all, scheduler, container.notificationRepo, container.occurrenceRepo, Clock.System.now())

        assertTrue("expected boot re-sync to re-register the row", scheduler.scheduled.any { it.id == n.id })
    }

    @Test
    fun bootReSyncStillFiresAReminderMissedWhileTheDeviceWasOff() = runTest {
        // §10.4 on the path that actually loses reminders: the phone was off
        // across the reminder's fire time, but the event is still under way.
        // The row must reach AlarmManager (a past trigger time fires at once),
        // not be written off as MISSED (issue #48).
        val scheduler = FakeAlarmScheduler(canScheduleExact = true)
        container.alarmScheduler = scheduler
        val now = Clock.System.now()
        val overdue = freshNotification(now - 30.minutes, NotificationStatus.PENDING, Precision.EXACT)
        container.notificationRepo.upsert(overdue)
        container.occurrenceRepo.upsert(openWindowOccurrence(overdue.occurrenceId, now), firstSeenAt = now - 1.hours)

        AlarmSyncer.sync(listOf(overdue), scheduler, container.notificationRepo, container.occurrenceRepo, now)

        assertTrue("expected the missed reminder to be registered", scheduler.scheduled.any { it.id == overdue.id })
        assertEquals(NotificationStatus.REGISTERED, container.notificationRepo.getById(overdue.id)?.status)
    }

    @Test
    fun bootReSyncWritesOffAReminderWhoseEventIsOver() = runTest {
        // The other half of §10.4: once the occurrence's window has closed the
        // reminder really is stale, and MISSED is where it belongs.
        val scheduler = FakeAlarmScheduler(canScheduleExact = true)
        container.alarmScheduler = scheduler
        val now = Clock.System.now()
        val stale = freshNotification(now - 3.hours, NotificationStatus.PENDING, Precision.EXACT)
        container.notificationRepo.upsert(stale)
        container.occurrenceRepo.upsert(closedWindowOccurrence(stale.occurrenceId, now), firstSeenAt = now - 4.hours)

        AlarmSyncer.sync(listOf(stale), scheduler, container.notificationRepo, container.occurrenceRepo, now)

        assertTrue("expected no alarm for a stale reminder", scheduler.scheduled.none { it.id == stale.id })
        assertEquals(NotificationStatus.MISSED, container.notificationRepo.getById(stale.id)?.status)
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
        container.blockNotifications()
        val n = freshNotification(Clock.System.now(), NotificationStatus.REGISTERED, Precision.EXACT)
        container.notificationRepo.upsert(n)

        NotificationPoster.postNotificationFor(context, container, n.id)

        val stored = container.notificationRepo.getById(n.id)
        assertEquals(NotificationStatus.MISSED, stored?.status)
        assertNull("a reminder nobody saw must not carry a fired timestamp", stored?.firedAt)
        assertTrue("expected nothing to be posted while notifications are blocked", context.awaitNoPost(n.id))
    }

    /** MISSED is terminal (§10.4), so a duplicate alarm must not resurrect it. */
    @Test
    fun missedRowIsNotPostedByALateDuplicateAlarm() = runTest {
        val n = freshNotification(Clock.System.now(), NotificationStatus.MISSED, Precision.EXACT)
        container.notificationRepo.upsert(n)

        NotificationPoster.postNotificationFor(context, container, n.id)

        assertEquals(NotificationStatus.MISSED, container.notificationRepo.getById(n.id)?.status)
        assertTrue("a MISSED row must stay missed", context.awaitNoPost(n.id))
    }

    /**
     * §10.5's approximate hedge appends its "enable exact alarms" sentence on
     * the first APPROXIMATE notification *ever*. Spending that one chance on a
     * notification the OS then refused to show would silently lose it, so the
     * blocked check has to come before the body is rendered.
     */
    @Test
    fun blockedNotificationDoesNotConsumeTheOnceEverApproximateHedge() = runTest {
        container.blockNotifications()
        container.settingsRepo.delete(NotificationPoster.KEY_APPROXIMATE_HEDGE_SHOWN)
        val n = freshNotification(Clock.System.now(), NotificationStatus.REGISTERED, Precision.APPROXIMATE)
        container.notificationRepo.upsert(n)

        NotificationPoster.postNotificationFor(context, container, n.id)

        assertNull("the hedge must still be owed to the user", container.settingsRepo.get(NotificationPoster.KEY_APPROXIMATE_HEDGE_SHOWN))
    }
}
