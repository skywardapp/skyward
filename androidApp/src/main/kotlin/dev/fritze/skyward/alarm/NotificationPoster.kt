package dev.fritze.skyward.alarm

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.fritze.skyward.R
import dev.fritze.skyward.core.format.applyApproximateHedge
import dev.fritze.skyward.core.model.NotificationStatus
import dev.fritze.skyward.core.model.Precision
import dev.fritze.skyward.data.AppContainer
import kotlin.time.Clock

/**
 * §10.4/§10.5's fire-time step, shared by the exact path
 * ([NotificationAlarmReceiver]) and the approximate path
 * ([NotificationFireWorker]) so the two never drift apart.
 */
object NotificationPoster {

    suspend fun postNotificationFor(context: Context, container: AppContainer, notificationId: String) {
        val notification = container.notificationRepo.getById(notificationId) ?: return
        // A race is possible: the alarm/work item fired just as a replan
        // cancelled this row (occurrence withdrawn, rule disabled, muted).
        if (notification.status == NotificationStatus.CANCELLED || notification.status == NotificationStatus.FIRED) return

        val occurrence = container.occurrenceRepo.getById(notification.occurrenceId)
        val channelId = occurrence?.let { NotificationChannels.channelIdFor(it) } ?: NotificationChannels.DIAGNOSTICS_CHANNEL_ID

        val body = if (notification.precision == Precision.APPROXIMATE) {
            val alreadyShown = container.settingsRepo.get(KEY_APPROXIMATE_HEDGE_SHOWN) == "true"
            if (!alreadyShown) container.settingsRepo.set(KEY_APPROXIMATE_HEDGE_SHOWN, "true")
            applyApproximateHedge(notification.body, isFirstApproximateEver = !alreadyShown)
        } else {
            notification.body
        }

        val androidNotification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(notification.title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            // Tapping opens this occurrence's detail screen; without it the
            // notification is inert and setAutoCancel below never fires.
            .setContentIntent(openEventPendingIntent(context, occurrence?.id))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        val canPost = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (canPost) {
            NotificationManagerCompat.from(context).notify(notification.id.hashCode(), androidNotification)
        }

        container.notificationRepo.updateStatus(notification.id, NotificationStatus.FIRED, Clock.System.now())
    }

    private const val KEY_APPROXIMATE_HEDGE_SHOWN = "approximate_hedge_shown"
}
