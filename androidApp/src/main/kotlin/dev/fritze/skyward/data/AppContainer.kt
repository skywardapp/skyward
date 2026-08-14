package dev.fritze.skyward.data

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import dev.fritze.skyward.alarm.AlarmScheduler
import dev.fritze.skyward.alarm.AlarmSyncer
import dev.fritze.skyward.alarm.AlarmWindowTopUpWorker
import dev.fritze.skyward.alarm.AndroidAlarmScheduler
import dev.fritze.skyward.alarm.RefreshWorker
import dev.fritze.skyward.core.model.Phenomenon
import dev.fritze.skyward.core.persistence.LocationRepo
import dev.fritze.skyward.core.persistence.NotificationRepo
import dev.fritze.skyward.core.persistence.OccurrenceRepo
import dev.fritze.skyward.core.persistence.RuleRepo
import dev.fritze.skyward.core.persistence.SettingsRepo
import dev.fritze.skyward.core.persistence.SkywardDatabase
import dev.fritze.skyward.core.persistence.SourceStateRepo
import dev.fritze.skyward.core.planner.ReplanCoordinator
import dev.fritze.skyward.core.rules.defaultRules
import dev.fritze.skyward.core.sources.ConjunctionSource
import dev.fritze.skyward.core.sources.EclipseSource
import dev.fritze.skyward.core.sources.EventSource
import dev.fritze.skyward.core.sources.MeteorShowerSource
import dev.fritze.skyward.core.sources.MoonEventSource
import dev.fritze.skyward.core.sources.SourceRunner
import dev.fritze.skyward.core.visibility.AuroraVisibilityModel
import dev.fritze.skyward.core.visibility.CometVisibilityModel
import dev.fritze.skyward.core.visibility.ConjunctionVisibilityModel
import dev.fritze.skyward.core.visibility.LunarEclipseVisibilityModel
import dev.fritze.skyward.core.visibility.MeteorShowerVisibilityModel
import dev.fritze.skyward.core.visibility.MoonEventVisibilityModel
import dev.fritze.skyward.core.visibility.SolarEclipseVisibilityModel
import dev.fritze.skyward.core.visibility.TerrestrialVisibilityModel
import dev.fritze.skyward.core.visibility.VisibilityModel
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

    val visibilityModels: Map<Phenomenon, VisibilityModel> = mapOf(
        Phenomenon.SOLAR_ECLIPSE to SolarEclipseVisibilityModel(),
        Phenomenon.LUNAR_ECLIPSE to LunarEclipseVisibilityModel(),
        Phenomenon.AURORA to AuroraVisibilityModel(),
        Phenomenon.METEOR_SHOWER to MeteorShowerVisibilityModel(),
        Phenomenon.COMET to CometVisibilityModel(),
        Phenomenon.MOON_EVENT to MoonEventVisibilityModel(),
        Phenomenon.CONJUNCTION to ConjunctionVisibilityModel(),
        Phenomenon.TERRESTRIAL to TerrestrialVisibilityModel(),
    )

    // AURORA/COMET/EONET are POLLED sources; they land in M4 (§18). Their
    // visibility models are wired above regardless (M2's own scope), they
    // just never see any occurrences via SourceRunner until then.
    val computedSources: List<EventSource> = listOf(EclipseSource(), MeteorShowerSource(), MoonEventSource(), ConjunctionSource())

    /** Var (not val): instrumented tests substitute a fake per §17.5, since there's no DI framework. */
    var alarmScheduler: AlarmScheduler = AndroidAlarmScheduler(appContext)

    val replanCoordinator = ReplanCoordinator(occurrenceRepo, locationRepo, ruleRepo, notificationRepo, visibilityModels)

    /** One scope per app process (§4.3); sources never launch their own. */
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val sourceRunner = SourceRunner(
        computedSources, occurrenceRepo, sourceStateRepo, settingsRepo, ruleRepo, locationRepo,
        onOccurrencesChanged = { now -> replanAndSync(now) },
    )

    /** §9.7: recompute the desired/reconciled notification set and sync it onto real OS alarms. */
    suspend fun replanAndSync(now: Instant = Clock.System.now()) {
        val reconciled = replanCoordinator.replan(now)
        AlarmSyncer.sync(reconciled, alarmScheduler, notificationRepo, now)
    }

    /**
     * §10.2: one periodic `skyward-refresh` unique work (15min floor — no
     * POLLED source exists yet to derive a tighter interval from, M4) plus
     * the daily alarm-window top-up job.
     */
    fun scheduleBackgroundWork() {
        val workManager = WorkManager.getInstance(appContext)
        val refreshRequest = PeriodicWorkRequestBuilder<RefreshWorker>(15.minutes.toJavaDuration())
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
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
