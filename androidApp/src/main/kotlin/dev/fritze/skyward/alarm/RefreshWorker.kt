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
        // Force every COMPUTED source on each periodic pass: per SourceRunner.runDue's own
        // contract, an OnHorizonChange source never becomes due again on its own after a
        // successful run, so without this the rolling horizon window would stop revealing new
        // occurrences at its far edge after the very first refresh. Cheap (local astronomy,
        // no network) -- POLLED sources (M4) are unaffected and still run on their own schedule.
        container.sourceRunner.runDue(Clock.System.now(), force = container.computedSources.map { it.id }.toSet())
        return Result.success()
    }

    companion object {
        const val UNIQUE_WORK_NAME = "skyward-refresh"
    }
}
