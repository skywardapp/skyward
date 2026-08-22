package dev.fritze.skyward.alarm

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.fritze.skyward.SkywardApplication
import dev.fritze.skyward.core.model.NotificationStatus
import dev.fritze.skyward.core.model.PlannedNotification
import dev.fritze.skyward.core.model.Precision
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours

/**
 * §17.5's "boot-receiver re-registration" and §10.2's permission-change
 * re-plan, executed as real broadcasts to the real, manifest-declared
 * receivers -- not re-implemented in the test body.
 *
 * Three different dispatch mechanisms appear here, because the OS allows
 * exactly one route to each (ADR 0018):
 *
 * - `BOOT_COMPLETED` / `MY_PACKAGE_REPLACED` are `<protected-broadcast>`s, so
 *   only a root shell can send them; those tests skip honestly where adbd
 *   cannot be rooted, and [everyReceiverIsDeclaredForTheActionsItGuards]
 *   runs unconditionally so a skip can never mean no coverage at all.
 * - The exact-alarm permission change needs no simulated broadcast at all:
 *   moving the app-op makes `AlarmManagerService` send the real one. That test
 *   lives in [RealAlarmSchedulingTest] rather than here, even though it is a
 *   receiver test, because the grant is one-way and has to be ordered against
 *   every other test that reads the exact-alarm state (ADR 0018).
 * - A foreign action is dispatched in-process, since it is not protected --
 *   which is exactly the attack the exported receivers' guards exist to stop.
 */
@RunWith(AndroidJUnit4::class)
class SystemBroadcastReceiverTest {

    private val context = ApplicationProvider.getApplicationContext<SkywardApplication>()
    private val container = context.container

    private val seededNotificationIds = mutableListOf<String>()

    @After
    fun restoreSharedState() = runTest {
        for (id in seededNotificationIds) {
            AndroidAlarmScheduler(context).cancel(id)
            container.notificationRepo.deleteById(id)
        }
        seededNotificationIds.clear()
        container.alarmScheduler = AndroidAlarmScheduler(context)
        context.clearShade()
    }

    /**
     * The backstop, and the only test here that can never skip: whatever the
     * shell is allowed to send, the three receivers must still be declared for
     * the actions they exist to handle. A receiver dropped from the manifest
     * would otherwise show up only as reminders that stop surviving reboots.
     */
    @Test
    fun everyReceiverIsDeclaredForTheActionsItGuards() {
        assertDeclared(Intent.ACTION_BOOT_COMPLETED, BootReceiver::class.java)
        assertDeclared(Intent.ACTION_MY_PACKAGE_REPLACED, BootReceiver::class.java)
        assertDeclared(EXACT_ALARM_PERMISSION_STATE_CHANGED, ExactAlarmPermissionReceiver::class.java)
    }

    /**
     * §10.2: "On BOOT_COMPLETED ...: re-register the window." A reboot wipes
     * every `AlarmManager` alarm while the desired set in the database stays
     * correct, so the receiver re-syncs one onto the other.
     *
     * The scheduler is a fake on purpose: what is under test is that the real
     * receiver ran and reached [AlarmSyncer], not what `AlarmManager` does with
     * the result -- [RealAlarmSchedulingTest] covers that half.
     */
    @Test
    fun bootCompletedReRegistersTheWindow() = runTest {
        assertReSyncOnProtectedBroadcast(Intent.ACTION_BOOT_COMPLETED, BootReceiver::class.java)
    }

    /** §10.2 names `MY_PACKAGE_REPLACED` alongside boot, for the same reason: an update wipes the alarms too. */
    @Test
    fun packageReplacedReRegistersTheWindow() = runTest {
        assertReSyncOnProtectedBroadcast(Intent.ACTION_MY_PACKAGE_REPLACED, BootReceiver::class.java)
    }

    /**
     * Both system receivers are `exported="true"` because they have to hear
     * the system -- and an exported receiver can be targeted by any app with an
     * explicit intent carrying any action at all. Their action guards are what
     * stop that from becoming a free "make Skyward re-sync every alarm" button,
     * and this is the test those guards' comments ask for.
     *
     * Dispatched in-process precisely because a foreign action is *not*
     * protected: that is the same door a hostile app would come through.
     */
    @Test
    fun aForeignActionOnTheExportedReceiversChangesNothing() = runTest {
        val fake = FakeAlarmScheduler(canScheduleExact = true)
        container.alarmScheduler = fake
        val n = seed(freshNotification(Clock.System.now() + 1.hours))

        context.sendBroadcast(Intent(context, BootReceiver::class.java).setAction(FOREIGN_ACTION))
        context.sendBroadcast(Intent(context, ExactAlarmPermissionReceiver::class.java).setAction(FOREIGN_ACTION))

        Thread.sleep(ABSENCE_GRACE_MILLIS)
        // Asserted against this row's own id rather than "the fake recorded
        // nothing": the app's periodic `skyward-refresh` work is live in this
        // process and could in principle re-plan through the same pinned fake
        // while the grace window is open. That would be unrelated traffic; a
        // guard that let this row through would not be.
        assertTrue("a foreign action must not re-register ${n.id}", fake.scheduled.none { it.id == n.id })
        assertTrue("a foreign action must not cancel ${n.id} either", fake.cancelled.none { it == n.id })
        assertEquals("the row must still be untouched", NotificationStatus.PENDING, container.notificationRepo.getById(n.id)?.status)
    }

    private suspend fun assertReSyncOnProtectedBroadcast(action: String, receiver: Class<*>) {
        assumeTrue(
            "$action is a protected broadcast; this device's adbd is not rooted, so no test process can send one (ADR 0018)",
            canSendProtectedBroadcasts,
        )
        val fake = FakeAlarmScheduler(canScheduleExact = true)
        container.alarmScheduler = fake
        val n = seed(freshNotification(Clock.System.now() + 1.hours))

        assertTrue("$action was not dispatched", sendProtectedBroadcast(context.packageName, action, receiver))

        // goAsync() finishes after onReceive returns -- and `am broadcast`
        // returns when onReceive does -- so the only sound assertion is a
        // polled one on the effect.
        val status = awaitValue(
            timeoutMillis = RECEIVER_TIMEOUT_MILLIS,
            read = { container.notificationRepo.getById(n.id)?.status },
        ) { it == NotificationStatus.REGISTERED }
        assertEquals("$action must re-register the 14-day window", NotificationStatus.REGISTERED, status)
        assertTrue("the row must have reached the scheduler", fake.scheduled.any { it.id == n.id })
    }

    private fun assertDeclared(action: String, receiver: Class<*>) {
        val intent = Intent(action).setPackage(context.packageName)
        val resolved = context.packageManager.queryBroadcastReceivers(intent, 0)
        val match = resolved.firstOrNull { it.activityInfo.name == receiver.name }
        assertTrue("${receiver.simpleName} must be declared for $action", match != null)
        assertTrue("${receiver.simpleName} must be exported to hear the system", match!!.activityInfo.exported)
    }

    private suspend fun seed(n: PlannedNotification) = n.also {
        container.notificationRepo.upsert(it)
        seededNotificationIds += it.id
    }

    private companion object {
        /** Kept in sync with ExactAlarmPermissionReceiver's own literal; the constant is API 31+. */
        const val EXACT_ALARM_PERMISSION_STATE_CHANGED = "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED"

        const val FOREIGN_ACTION = "dev.fritze.skyward.test.NOT_AN_ACTION_WE_HANDLE"

        /** A `goAsync()` receiver has to read the table and write every row back; give it room without waiting on OS scheduling. */
        const val RECEIVER_TIMEOUT_MILLIS = 15_000L
    }
}
