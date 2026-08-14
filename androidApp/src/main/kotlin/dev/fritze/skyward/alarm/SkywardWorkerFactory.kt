package dev.fritze.skyward.alarm

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import dev.fritze.skyward.data.AppContainer

/** Constructor-injects [AppContainer] into workers that need repo/scheduler access, since WorkManager's default factory only knows no-arg constructors. */
class SkywardWorkerFactory(private val container: AppContainer) : WorkerFactory() {
    override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker? =
        when (workerClassName) {
            NotificationFireWorker::class.java.name -> NotificationFireWorker(appContext, workerParameters, container)
            RefreshWorker::class.java.name -> RefreshWorker(appContext, workerParameters, container)
            AlarmWindowTopUpWorker::class.java.name -> AlarmWindowTopUpWorker(appContext, workerParameters, container)
            else -> null // fall back to the default factory
        }
}
