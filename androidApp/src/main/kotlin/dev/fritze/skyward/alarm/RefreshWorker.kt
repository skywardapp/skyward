package dev.fritze.skyward.alarm

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dev.fritze.skyward.data.AppContainer
import kotlin.time.Clock

/**
 * §10.2's polling path: the periodic `skyward-refresh` unique work. Calls
 * `SourceRunner.runDue()`; a material change re-plans and re-syncs alarms
 * via the container's `onOccurrencesChanged` callback.
 */
class RefreshWorker(
    context: Context,
    params: WorkerParameters,
    private val container: AppContainer,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        container.sourceRunner.runDue(Clock.System.now())
        return Result.success()
    }

    companion object {
        const val UNIQUE_WORK_NAME = "skyward-refresh"
    }
}
