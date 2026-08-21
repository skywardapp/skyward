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
        // Nothing is forced here. Every source, COMPUTED included, now carries its
        // own next_run_at (the COMPUTED ones a daily one, ADR 0009), so due-ness
        // alone keeps the rolling horizon window moving. Forcing the COMPUTED
        // sources every pass instead re-ran EclipseSource's path sampling -- minutes
        // of CPU, not the "cheap local astronomy" this comment used to claim -- and
        // WorkManager's ~10-minute ceiling for a non-expedited worker could cut the
        // pass off before the POLLED sources listed after them ever ran (issue #49).
        container.sourceRunner.runDue(Clock.System.now())
        return Result.success()
    }

    companion object {
        const val UNIQUE_WORK_NAME = "skyward-refresh"
    }
}
