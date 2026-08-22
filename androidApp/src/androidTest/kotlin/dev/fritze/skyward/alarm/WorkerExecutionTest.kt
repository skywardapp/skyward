package dev.fritze.skyward.alarm

import android.Manifest
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import dev.fritze.skyward.SkywardApplication
import dev.fritze.skyward.core.model.NotificationStatus
import dev.fritze.skyward.core.model.PlannedNotification
import dev.fritze.skyward.core.model.Precision
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

/**
 * §17.5 / #55: the three [androidx.work.CoroutineWorker]s actually run.
 *
 * Until this suite existed, [NotificationFireWorker], [RefreshWorker] and
 * [AlarmWindowTopUpWorker] were executed by no test at all -- and neither was
 * [SkywardWorkerFactory], whose whole job is to inject
 * [dev.fritze.skyward.data.AppContainer] into them (ADR 0006). A typo in its
 * class-name routing returns null, WorkManager's default factory then fails to
 * construct a worker with a three-argument constructor, and every approximate
 * reminder silently stops arriving -- with the old test suite still green.
 * So every worker here is built *through the real factory*, never with `new`.
 *
 * [androidx.work.testing.WorkManagerTestInitHelper] is deliberately not used:
 * ADR 0006 has WorkManager initialising lazily from
 * `AppContainer.scheduleBackgroundWork()` during `Application.onCreate`, so it
 * is always already initialised before a test runs and
 * `initializeTestWorkManager()` would throw. [TestListenableWorkerBuilder]
 * needs no such initialisation. See ADR 0018.
 */
@RunWith(AndroidJUnit4::class)
class WorkerExecutionTest {

    // POST_NOTIFICATIONS only exists from API 33; below that it is implicitly granted and
    // asking UiAutomation to grant it fails outright, so the rule has to be a no-op on
    // older emulators rather than an unconditional grant.
    @get:Rule
    val permissionRule: TestRule =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            TestRule { base, _ -> base }
        }

    private val context = ApplicationProvider.getApplicationContext<SkywardApplication>()
    private val container = context.container
    private val factory get() = SkywardWorkerFactory(container)

    /** Ids this test seeded, so [restoreSharedState] can take them back out again. */
    private val seededNotificationIds = mutableListOf<String>()

    /** Source enablement as found, so the [RefreshWorker] test can put it back. */
    private var sourceEnablementBefore: Map<String, Boolean> = emptyMap()

    @Before
    fun startFromAKnownShade() {
        container.restoreRealNotificationGate(context)
        assertTrue("notifications did not clear before the test started", context.clearShade().isEmpty())
    }

    @After
    fun restoreSharedState() = runTest {
        container.restoreRealNotificationGate(context)
        container.alarmScheduler = AndroidAlarmScheduler(context)
        for (id in seededNotificationIds) {
            WorkManager.getInstance(context).cancelUniqueWork(approximateWorkName(id))
            container.notificationRepo.deleteById(id)
        }
        seededNotificationIds.clear()
        for ((sourceId, enabled) in sourceEnablementBefore) {
            container.settingsRepo.setSourceEnabled(sourceId, enabled)
        }
        sourceEnablementBefore = emptyMap()
        context.clearShade()
    }

    /**
     * Each worker is built through [SkywardWorkerFactory], not constructed
     * directly, and that is the assertion: `WorkerParameters` has no public
     * constructor, so `createWorker` cannot be called from a test -- but
     * [TestListenableWorkerBuilder] takes the same path WorkManager takes at
     * runtime. If the factory failed to route one of these class names it
     * would return null, WorkManager would fall through to its default
     * factory, and that one cannot construct a worker whose constructor takes
     * an [dev.fritze.skyward.data.AppContainer] -- so `build()` throws right
     * here instead of a reminder silently never arriving on a real device.
     */
    @Test
    fun theFactoryConstructsEveryWorkerThatNeedsTheContainer() {
        assertNotNull(TestListenableWorkerBuilder<NotificationFireWorker>(context).setWorkerFactory(factory).build())
        assertNotNull(TestListenableWorkerBuilder<RefreshWorker>(context).setWorkerFactory(factory).build())
        assertNotNull(TestListenableWorkerBuilder<AlarmWindowTopUpWorker>(context).setWorkerFactory(factory).build())
    }

    @Test
    fun notificationFireWorkerPostsTheReminderNamedByItsInput() = runTest {
        val n = seed(freshNotification(Clock.System.now(), NotificationStatus.REGISTERED, Precision.APPROXIMATE))

        val worker = TestListenableWorkerBuilder<NotificationFireWorker>(context)
            .setWorkerFactory(factory)
            .setInputData(workDataOf(NotificationFireWorker.KEY_NOTIFICATION_ID to n.id))
            .build()
        val result = worker.doWork()

        assertTrue("the approximate path must report success", result is ListenableWorker.Result.Success)
        assertNotNull("expected notification ${n.id.hashCode()} to be posted", context.awaitPosted(n.id))
        assertEquals(NotificationStatus.FIRED, container.notificationRepo.getById(n.id)?.status)
    }

    @Test
    fun notificationFireWorkerFailsWithoutANotificationId() = runTest {
        val worker = TestListenableWorkerBuilder<NotificationFireWorker>(context).setWorkerFactory(factory).build()

        // Failure, not retry: an input-less work item can never acquire one, so
        // retrying it would spin forever.
        assertTrue("a work item with no id must fail", worker.doWork() is ListenableWorker.Result.Failure)
    }

    @Test
    fun alarmWindowTopUpWorkerRegistersAReminderInsideTheWindow() = runTest {
        val fake = FakeAlarmScheduler(canScheduleExact = true)
        container.alarmScheduler = fake
        val inside = seed(freshNotification(Clock.System.now() + 1.hours))
        // §10.2's 14-day registration window: this one is deliberately beyond it.
        val outside = seed(freshNotification(Clock.System.now() + 20.days))

        val result = TestListenableWorkerBuilder<AlarmWindowTopUpWorker>(context).setWorkerFactory(factory).build().doWork()

        assertTrue(result is ListenableWorker.Result.Success)
        assertEquals(NotificationStatus.REGISTERED, container.notificationRepo.getById(inside.id)?.status)
        assertEquals(Precision.EXACT, container.notificationRepo.getById(inside.id)?.precision)
        assertTrue("the row inside the window must have been registered", fake.scheduled.any { it.id == inside.id })
        assertEquals(
            "a row 20 days out is not the top-up's business yet (§10.2)",
            NotificationStatus.PENDING,
            container.notificationRepo.getById(outside.id)?.status,
        )
        assertTrue("nothing beyond the window may be registered", fake.scheduled.none { it.id == outside.id })
    }

    /**
     * Every source is switched off first, and that is the point rather than a
     * shortcut: on a fresh emulator database every source is due, so a real
     * `runDue()` pass would issue live HTTPS to NOAA/JPL/EONET *and* re-run
     * `EclipseSource`'s path sampling -- minutes of CPU, per [RefreshWorker]'s
     * own note on issue #49. What this asserts is the half only a device can
     * answer: that the factory injects the container and `doWork()` honours its
     * contract. `SourceRunner`'s own behaviour is covered by `:core`'s tests.
     */
    @Test
    fun refreshWorkerCompletesWhenNoSourceIsDue() = runTest {
        val sources = container.computedSources + container.polledSources
        sourceEnablementBefore = sources.associate { it.id to container.settingsRepo.isSourceEnabled(it.id) }
        for (source in sources) container.settingsRepo.setSourceEnabled(source.id, false)

        val result = TestListenableWorkerBuilder<RefreshWorker>(context).setWorkerFactory(factory).build().doWork()

        assertTrue("the polling path must report success even with nothing to do", result is ListenableWorker.Result.Success)
    }

    /**
     * §10.2's two periodic jobs, as actually enqueued by
     * `AppContainer.scheduleBackgroundWork()` in `Application.onCreate`.
     * Nothing asserted this before: with the app's only call site being
     * startup, a job that stopped being registered would show up as reminders
     * quietly ageing out of the 14-day window months later.
     */
    @Test
    fun theAppRegistersItsTwoPeriodicJobsAtStartup() {
        for (name in listOf(RefreshWorker.UNIQUE_WORK_NAME, AlarmWindowTopUpWorker.UNIQUE_WORK_NAME)) {
            val infos = WorkManager.getInstance(context).getWorkInfosForUniqueWork(name).get()
            assertTrue("$name must be registered at startup", infos.isNotEmpty())
            assertTrue("$name must not be cancelled", infos.none { it.state == WorkInfo.State.CANCELLED })
        }
    }

    private suspend fun seed(n: PlannedNotification) = n.also {
        container.notificationRepo.upsert(it)
        seededNotificationIds += it.id
    }
}
