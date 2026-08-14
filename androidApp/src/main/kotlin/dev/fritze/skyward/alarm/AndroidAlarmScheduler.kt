package dev.fritze.skyward.alarm

import android.app.AlarmManager
import android.content.Context
import android.os.Build
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import dev.fritze.skyward.core.model.PlannedNotification
import dev.fritze.skyward.core.model.Precision
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.toJavaDuration

/** §10.2's two paths behind one interface. */
class AndroidAlarmScheduler(private val context: Context) : AlarmScheduler {
    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    override fun canScheduleExact(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()

    override fun schedule(n: PlannedNotification): Precision {
        cancelApproximate(n.id) // idempotent re-schedule may be switching paths; never leave both registered
        return if (canScheduleExact()) {
            val pendingIntent = notificationPendingIntent(context, n.id)
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, n.fireAt.toEpochMilliseconds(), pendingIntent)
            Precision.EXACT
        } else {
            alarmManager.cancel(notificationPendingIntent(context, n.id))
            scheduleApproximate(n)
            Precision.APPROXIMATE
        }
    }

    override fun cancel(id: String) {
        alarmManager.cancel(notificationPendingIntent(context, id))
        cancelApproximate(id)
    }

    private fun scheduleApproximate(n: PlannedNotification) {
        val delay = (n.fireAt - Clock.System.now()).let { if (it < Duration.ZERO) Duration.ZERO else it }
        val request = OneTimeWorkRequestBuilder<NotificationFireWorker>()
            .setInitialDelay(delay.toJavaDuration())
            .setInputData(workDataOf(NotificationFireWorker.KEY_NOTIFICATION_ID to n.id))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(approximateWorkName(n.id), ExistingWorkPolicy.REPLACE, request)
    }

    private fun cancelApproximate(id: String) {
        WorkManager.getInstance(context).cancelUniqueWork(approximateWorkName(id))
    }

    private fun approximateWorkName(id: String) = "notify:$id"
}
