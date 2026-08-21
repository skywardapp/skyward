package dev.fritze.skyward.data

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import dev.fritze.skyward.alarm.AlarmScheduler
import dev.fritze.skyward.alarm.AlarmSyncer
import dev.fritze.skyward.alarm.AlarmWindowTopUpWorker
import dev.fritze.skyward.alarm.AndroidAlarmScheduler
import dev.fritze.skyward.alarm.AndroidNotificationGate
import dev.fritze.skyward.alarm.NotificationGate
import dev.fritze.skyward.alarm.RefreshWorker
import dev.fritze.skyward.core.model.Phenomenon
import dev.fritze.skyward.core.persistence.LocationRepo
import dev.fritze.skyward.core.persistence.NotificationRepo
import dev.fritze.skyward.core.persistence.OccurrenceRepo
import dev.fritze.skyward.core.persistence.RuleRepo
import dev.fritze.skyward.core.persistence.SettingsRepo
import dev.fritze.skyward.core.persistence.SkywardDatabase
import dev.fritze.skyward.core.persistence.SourceStateRepo
import dev.fritze.skyward.core.persistence.SyncImportRepo
import dev.fritze.skyward.core.persistence.VisibilityCacheRepo
import dev.fritze.skyward.core.planner.ReplanCoordinator
import dev.fritze.skyward.core.rules.defaultRules
import dev.fritze.skyward.core.sources.AuroraSource
import dev.fritze.skyward.core.sources.EventSource
import dev.fritze.skyward.core.sources.SourceRunner
import dev.fritze.skyward.core.sources.defaultComputedSources
import dev.fritze.skyward.core.sources.defaultPolledSources
import dev.fritze.skyward.core.visibility.VisibilityModel
import dev.fritze.skyward.core.visibility.defaultVisibilityModels
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.time.toJavaDuration

/**
 * Composition root (§4.1: three modules only, no DI framework pulled in for
 * an app this size). One instance, held by [dev.fritze.skyward.SkywardApplication]
 * for the process lifetime.
 */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    private val driver = AndroidSqliteDriver(SkywardDatabase.Schema, appContext, "skyward.db")
    val database: SkywardDatabase = SkywardDatabase(driver)

    val locationRepo = LocationRepo(database)
    val occurrenceRepo = OccurrenceRepo(database)
    val ruleRepo = RuleRepo(database)
    val notificationRepo = NotificationRepo(database)
    val sourceStateRepo = SourceStateRepo(database)
    val settingsRepo = SettingsRepo(database)
    val syncImportRepo = SyncImportRepo(database)
    val visibilityCacheRepo = VisibilityCacheRepo(database)

    val visibilityModels: Map<Phenomenon, VisibilityModel> = defaultVisibilityModels

    // RefreshWorker force-runs exactly these every 15 min (§10.2) -- an
    // OnHorizonChange source never becomes due on its own, so the rolling
    // horizon window needs a periodic nudge. POLLED sources below are never
    // force-run; they rely entirely on SourceRunner.isDue (§6.2).
    val computedSources: List<EventSource> = defaultComputedSources

    // §18/M4: AURORA/COMET/EONET. Each derives its own next-run time
    // (AuroraSource's tiered poll, §7.3.2) or uses a fixed Schedule.Periodic
    // (comet monthly, EONET 6-hourly) -- SourceRunner treats them exactly
    // like the COMPUTED sources otherwise, including per-source failure
    // isolation, so no network-specific WorkManager wiring is needed here:
    // a fetch failure while offline is just another refresh() failure,
    // already handled by SourceRunner's diagnose+backoff path (§6.2).
    val polledSources: List<EventSource> = defaultPolledSources

    /** Var (not val): instrumented tests substitute a fake per §17.5, since there's no DI framework. */
    var alarmScheduler: AlarmScheduler = AndroidAlarmScheduler(appContext)

    /**
     * §10.1: whether reminders can reach the user at all, as opposed to
     * whether they can reach them on time (that's [alarmScheduler]). Read by
     * the fire path and by the warning cards. Var for the same reason.
     */
    var notificationGate: NotificationGate = AndroidNotificationGate(appContext)

    val replanCoordinator = ReplanCoordinator(
        occurrenceRepo, locationRepo, ruleRepo, notificationRepo, visibilityCacheRepo, visibilityModels,
        ovationGridProvider = { AuroraSource.loadOvationGrid(sourceStateRepo) },
    )

    /** One scope per app process (§4.3); sources never launch their own. */
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val sourceRunner = SourceRunner(
        computedSources + polledSources, occurrenceRepo, sourceStateRepo, settingsRepo, ruleRepo, locationRepo, visibilityCacheRepo,
        onOccurrencesChanged = { now -> replanAndSync(now) },
    )

    /** §9.7: recompute the desired/reconciled notification set and sync it onto real OS alarms. */
    suspend fun replanAndSync(now: Instant = Clock.System.now()) {
        val reconciled = replanCoordinator.replan(now)
        AlarmSyncer.sync(reconciled, alarmScheduler, notificationRepo, occurrenceRepo, now)
    }

    /**
     * §10.2: one periodic `skyward-refresh` unique work (15min floor —
     * matches AuroraSource's active-tier interval, §7.3.2) plus the daily
     * alarm-window top-up job.
     */
    fun scheduleBackgroundWork() {
        val workManager = WorkManager.getInstance(appContext)
        // No network constraint on the worker itself: it force-runs the
        // COMPUTED sources (local astronomy, no network) every pass
        // regardless, and POLLED sources' own network failures are already
        // handled per-source by SourceRunner's diagnose+backoff (§6.2) --
        // gating the whole worker on connectivity would also block that
        // COMPUTED horizon re-force while offline.
        val refreshRequest = PeriodicWorkRequestBuilder<RefreshWorker>(15.minutes.toJavaDuration()).build()
        workManager.enqueueUniquePeriodicWork(RefreshWorker.UNIQUE_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, refreshRequest)

        val topUpRequest = PeriodicWorkRequestBuilder<AlarmWindowTopUpWorker>(1.days.toJavaDuration()).build()
        workManager.enqueueUniquePeriodicWork(AlarmWindowTopUpWorker.UNIQUE_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, topUpRequest)
    }

    fun ensureDefaultRulesSeeded() {
        applicationScope.launch {
            if (settingsRepo.get(KEY_DEFAULT_RULES_SEEDED) != "true") {
                for (rule in defaultRules(Clock.System.now())) ruleRepo.upsert(rule)
                settingsRepo.set(KEY_DEFAULT_RULES_SEEDED, "true")
            }
        }
    }

    private companion object {
        const val KEY_DEFAULT_RULES_SEEDED = "default_rules_seeded"
    }
}
