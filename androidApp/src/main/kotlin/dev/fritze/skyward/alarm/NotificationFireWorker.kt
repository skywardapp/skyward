package dev.fritze.skyward.alarm

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dev.fritze.skyward.data.AppContainer

/** §10.2's approximate path: a one-off `WorkManager` job replacing an exact alarm the OS won't let us set. */
class NotificationFireWorker(
    context: Context,
    params: WorkerParameters,
    private val container: AppContainer,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val notificationId = inputData.getString(KEY_NOTIFICATION_ID) ?: return Result.failure()
        NotificationPoster.postNotificationFor(applicationContext, container, notificationId)
        return Result.success()
    }

    companion object {
        const val KEY_NOTIFICATION_ID = "notification_id"
    }
}
