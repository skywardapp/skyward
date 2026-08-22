package dev.fritze.skyward.alarm

import android.Manifest
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dev.fritze.skyward.SkywardApplication
import dev.fritze.skyward.core.model.NotificationStatus
import dev.fritze.skyward.core.model.PlannedNotification
import dev.fritze.skyward.core.model.Precision
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/**
 * §17.5 / #55: [AndroidAlarmScheduler] and [NotificationAlarmReceiver] doing
 * their real work, against the real `AlarmManager`, `WorkManager` and
 * `NotificationManager`.
 *
 * The sibling suite [AlarmFlowInstrumentedTest] substitutes
 * [FakeAlarmScheduler] and stands in for the receiver body, which is what
 * §17.5 asks for and is still the only way to reach states the OS will not
 * produce on demand. What it cannot show is that the *plumbing between* those
 * pieces exists: a wrong action prefix, a receiver missing from the manifest,
 * a `goAsync()` that never finishes, or a `PendingIntent` whose identity does
 * not match what `AlarmManager.cancel` looks for would all leave that suite
 * green. This one fails on any of them.
 *
 * §17.5's "fire the receiver directly" is not literally possible:
 * `goAsync()` returns null unless the system dispatched the broadcast, and
 * these receivers would then throw inside `applicationScope` -- taking the
 * test process with them. Every dispatch here is therefore a real one. See
 * ADR 0018.
 */
@RunWith(AndroidJUnit4::class)
class RealAlarmSchedulingTest {

    @get:Rule
    val permissionRule: TestRule =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            TestRule { base, _ -> base }
        }

    private val context = ApplicationProvider.getApplicationContext<SkywardApplication>()
    private val container = context.container
    private val scheduler = AndroidAlarmScheduler(context)

    private val seededNotificationIds = mutableListOf<String>()

    @Before
    fun startFromAKnownShade() {
        container.restoreRealNotificationGate(context)
        assertTrue("notifications did not clear before the test started", context.clearShade().isEmpty())
    }

    /**
     * Everything this suite registers is a *real* OS alarm and a *real* work
     * item, both of which outlive the test method and even the test process.
     * A `notify:<id>` left enqueued would fire minutes later, during some other
     * class, and break its "the shade is empty" precondition -- so cancel both
     * halves, and delete the row as well: [NotificationPoster] returns without
     * posting when `getById` finds nothing, which makes a row-less escapee
     * harmless even if a cancel loses a race.
     */
    @After
    fun cancelEverythingThisSuiteRegistered() = runTest {
        for (id in seededNotificationIds) {
            scheduler.cancel(id)
            container.notificationRepo.deleteById(id)
        }
        seededNotificationIds.clear()
        container.restoreRealNotificationGate(context)
        container.alarmScheduler = AndroidAlarmScheduler(context)
        context.clearShade()
    }

    /**
     * §10.2's exact path keeps a WorkManager job registered *as well*, which
     * [AndroidAlarmScheduler]'s longest comment explains at length and no test
     * has ever checked: revoking the exact-alarm permission deletes the OS
     * alarm without broadcasting anything, so without that standing fallback a
     * mid-life revocation would silently drop the reminder.
     */
    @Test
    fun theExactPathAlsoLeavesAWorkManagerBackupStanding() = runTest {
        assumeTrue("this device cannot schedule exact alarms", scheduler.canScheduleExact())
        val n = seed(freshNotification(Clock.System.now() + 1.hours))

        assertEquals(Precision.EXACT, scheduler.schedule(n))

        val infos = WorkManager.getInstance(context).getWorkInfosForUniqueWork(approximateWorkName(n.id)).get()
        assertTrue("the exact path must still register its WorkManager backup", infos.isNotEmpty())
        assertTrue("the backup must be waiting, not finished", infos.any { it.state == WorkInfo.State.ENQUEUED })

        scheduler.cancel(n.id)
        assertTrue("cancel() must take the backup with it", awaitWorkCancelled(approximateWorkName(n.id)))
    }

    /**
     * The full exact path: real `setExactAndAllowWhileIdle`, real dispatch to
     * the manifest-declared [NotificationAlarmReceiver], real
     * [NotificationPoster], real notification.
     *
     * Attribution is arranged by construction rather than by racing. Because
     * `schedule()` always enqueues the WorkManager backup too, a notification
     * appearing would otherwise prove nothing about *which* path delivered it.
     * So the row is deliberately not written until after the backup has been
     * cancelled -- and even if that cancel lost the race, the worker would find
     * no row, return without posting, and leave the shade empty. A posted
     * notification can therefore only have come from `AlarmManager`.
     */
    @Test
    fun systemDeliveryOfTheExactAlarmPostsThroughTheRealReceiver() = runTest {
        assumeTrue("this device cannot schedule exact alarms", scheduler.canScheduleExact())
        val n = freshNotification(Clock.System.now() + DELIVERY_LEAD, NotificationStatus.REGISTERED)
        seededNotificationIds += n.id

        assertEquals(Precision.EXACT, scheduler.schedule(n))

        val workName = approximateWorkName(n.id)
        WorkManager.getInstance(context).cancelUniqueWork(workName)
        assertTrue("the WorkManager twin must be out of the way before the row exists", awaitWorkCancelled(workName))

        container.notificationRepo.upsert(n)

        assertNotNull(
            "AlarmManager must have woken NotificationAlarmReceiver and posted ${n.id.hashCode()}",
            context.awaitPosted(n.id, DELIVERY_LEAD.inWholeMilliseconds + SCHEDULED_DELIVERY_TIMEOUT_MILLIS),
        )
        assertEquals(NotificationStatus.FIRED, container.notificationRepo.getById(n.id)?.status)
    }

    /**
     * The same receiver, dispatched by the system but without waiting on
     * `AlarmManager`'s scheduling pass -- so it runs in milliseconds and is
     * the deterministic half of the receiver's coverage. This is a genuine
     * `ActivityThread` dispatch (hence a working `goAsync()`): the action
     * prefix is this app's own rather than a `<protected-broadcast>`, and the
     * receiver, though `exported="false"`, is reachable from its own uid.
     */
    @Test
    fun theReceiverPostsWhateverTheSystemHandsIt() = runTest {
        val n = seed(freshNotification(Clock.System.now(), NotificationStatus.REGISTERED))

        context.sendBroadcast(
            Intent(context, NotificationAlarmReceiver::class.java).setAction(ACTION_PREFIX + n.id),
        )

        assertNotNull("the real receiver must post ${n.id.hashCode()}", context.awaitPosted(n.id))
        assertEquals(NotificationStatus.FIRED, container.notificationRepo.getById(n.id)?.status)
    }

    /**
     * §17.5's headline denied-permission scenario, produced by the OS instead
     * of by a fake: the row is marked APPROXIMATE, the WorkManager path
     * delivers it, and §10.5's hedge is rendered in the copy the user sees.
     *
     * Skips where the state is not producible rather than passing quietly --
     * on `foss` at API 33+, `USE_EXACT_ALARM` outranks the app-op by design.
     * See [exactAlarmDenialIsProducible].
     */
    @Test
    fun aDeniedPermissionTakesTheWorkManagerPathAndRendersTheHedge() = runTest {
        assumeTrue(
            "exact alarms cannot be denied on this flavour/API (foss holds USE_EXACT_ALARM from 33; the op does not exist below 31)",
            exactAlarmDenialIsProducible(context),
        )
        context.denyExactAlarms()
        assumeTrue("the app-op did not take effect", !scheduler.canScheduleExact())
        // The hedge is owed once ever (§10.5) and another suite may already
        // have spent it on this shared database.
        container.settingsRepo.delete(NotificationPoster.KEY_APPROXIMATE_HEDGE_SHOWN)
        // Just ahead of now, not at now: a fireAt already in the past with no
        // occurrence row behind it is §10.4's "the moment is gone" case, and
        // AlarmSyncer would mark it MISSED rather than register it.
        val n = seed(freshNotification(Clock.System.now() + APPROXIMATE_LEAD, NotificationStatus.PENDING))

        AlarmSyncer.sync(listOf(n), scheduler, container.notificationRepo, container.occurrenceRepo, Clock.System.now())

        assertEquals(Precision.APPROXIMATE, container.notificationRepo.getById(n.id)?.precision)
        assertEquals(NotificationStatus.REGISTERED, container.notificationRepo.getById(n.id)?.status)
        val posted = context.awaitPosted(n.id, SCHEDULED_DELIVERY_TIMEOUT_MILLIS)
        assertNotNull("the WorkManager path must still deliver the reminder", posted)
        val body = posted!!.notification.extras.getCharSequence(NotificationCompat.EXTRA_TEXT)?.toString().orEmpty()
        // The whole hedge, not merely the word: the fixture body says "at
        // 02:30", so "around 02:30" can only come from §10.5 having run.
        assertTrue("an APPROXIMATE reminder must hedge its time (§10.5), but was: $body", body.contains("around 02:30"))
        assertTrue(
            "the first APPROXIMATE reminder ever must also explain itself (§10.5), but was: $body",
            body.contains("Times are approximate"),
        )
    }

    /**
     * The user-visible half of the asymmetry [AndroidAlarmScheduler]'s
     * standing-backup comment is built around: revoking the exact-alarm
     * permission mid-life deletes the OS alarm and broadcasts *nothing* (the
     * OS only announces grants), so nothing re-plans -- and the reminder must
     * arrive anyway, off the WorkManager job registered alongside it.
     *
     * Deliberately asserts delivery rather than which path delivered: whether
     * the OS drops an already-set alarm on revocation is its business, not
     * this app's, and pinning that would make the test a change-detector for
     * platform behaviour. What §10.1 promises the user -- "never silently
     * dropped" -- is what is checked.
     */
    @Test
    fun revokingThePermissionMidFlightStillDeliversTheReminder() = runTest {
        assumeTrue("exact alarms cannot be denied on this flavour/API", exactAlarmDenialIsProducible(context))
        context.allowExactAlarms()
        assumeTrue("this device cannot schedule exact alarms", scheduler.canScheduleExact())
        val n = seed(freshNotification(Clock.System.now() + ALARM_LEAD, NotificationStatus.REGISTERED))
        assertEquals(Precision.EXACT, scheduler.schedule(n))

        context.denyExactAlarms()

        assertNotNull(
            "the standing WorkManager backup must survive the revocation",
            context.awaitPosted(n.id, SCHEDULED_DELIVERY_TIMEOUT_MILLIS),
        )
    }

    /**
     * §17.5: "this is the default state of the `play` flavour and must not be
     * an afterthought." Asserted with no app-op fiddling at all, so what it
     * measures is the *shipped* manifest split (D13, §10.2) rather than
     * anything the test arranged.
     *
     * API 34 is the earliest level where this is true: 31 and 32 pre-grant
     * `SCHEDULE_EXACT_ALARM` at install, and below 31 the permission does not
     * exist. (§10.2's "always the initial state for the `play` flavour on API
     * 31+" is optimistic on that point -- ADR 0018.)
     */
    @Test
    fun theDefaultInstallStateOfThisFlavourMatchesItsManifest() {
        assumeTrue("only API 34+ denies SCHEDULE_EXACT_ALARM at install", Build.VERSION.SDK_INT >= UPSIDE_DOWN_CAKE)

        if (holdsUseExactAlarm(context)) {
            assertTrue("foss declares USE_EXACT_ALARM, so exact alarms must be granted at install", scheduler.canScheduleExact())
        } else {
            assertTrue("play must start out denied -- that is the state §17.5 says must not be an afterthought", !scheduler.canScheduleExact())
        }
    }

    private suspend fun seed(n: PlannedNotification) = n.also {
        container.notificationRepo.upsert(it)
        seededNotificationIds += it.id
    }

    /** `cancelUniqueWork` is asynchronous; the state lands a moment after the call returns. */
    private suspend fun awaitWorkCancelled(workName: String): Boolean =
        awaitValue(read = { WorkManager.getInstance(context).getWorkInfosForUniqueWork(workName).get() }) { infos ->
            infos.all { it.state == WorkInfo.State.CANCELLED }
        }.all { it.state == WorkInfo.State.CANCELLED }

    private companion object {
        /**
         * Generous on purpose. The delivery test has to cancel the WorkManager
         * twin and *wait for that cancel to land* before it writes the row, and
         * only then can the alarm be allowed to fire -- an alarm arriving first
         * would find no row, post nothing, and fail the test for a reason that
         * has nothing to do with the code under test. Twenty seconds is far
         * more headroom than the cancel needs and still a small share of the
         * job's budget; an alarm in the past would have none at all.
         */
        val DELIVERY_LEAD = 20.seconds

        /** The revocation test seeds its row first, so it only needs time to revoke before the alarm fires. */
        val ALARM_LEAD = 6.seconds

        /** Long enough that `AlarmSyncer` registers rather than misses the row, short enough to wait out. */
        val APPROXIMATE_LEAD = 3.seconds

        /** `Build.VERSION_CODES.UPSIDE_DOWN_CAKE` as a literal: the constant is API 34+, this compiles against minSdk 26. */
        const val UPSIDE_DOWN_CAKE = 34
    }
}
