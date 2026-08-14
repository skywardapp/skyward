package dev.fritze.skyward.alarm

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dev.fritze.skyward.data.AppContainer
import kotlin.time.Clock

/**
 * §10.2: "the daily WorkManager job tops up the 14-day window" — rows that
 * were desired but too far out to register OS alarms for last time may
 * have entered the 14-day window since. No re-planning needed, just a
 * fresh [AlarmSyncer.sync] pass over what's already in the DB.
 */
class AlarmWindowTopUpWorker(
    context: Context,
    params: WorkerParameters,
    private val container: AppContainer,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val reconciled = container.notificationRepo.getAll()
        AlarmSyncer.sync(reconciled, container.alarmScheduler, container.notificationRepo, Clock.System.now())
        return Result.success()
    }

    companion object {
        const val UNIQUE_WORK_NAME = "skyward-alarm-window-topup"
    }
}
