package dev.fritze.skyward.alarm

import android.annotation.SuppressLint
import android.app.Notification
import android.content.Context
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
        // MISSED is terminal for the same reason FIRED is (§10.4): its moment
        // has passed, so a late-arriving duplicate must not resurrect it.
        if (notification.status.isTerminal()) return

        // Checked before anything else, and before the row is touched: §10.1
        // promises reminders are "never silently dropped", and a notification
        // the OS will refuse to show is exactly that. Recording it FIRED would
        // write a delivery into history that never happened — and §12.3
        // exports FIRED keys, so it would teach a second device not to notify
        // either. MISSED is §10.4's existing status for "the moment passed
        // without reaching you", and the warning cards in Upcoming and
        // Settings › Notifications explain the cause while it lasts.
        //
        // Bailing here also protects the once-ever APPROXIMATE hedge below:
        // rendering the body first would burn that "already shown" flag on a
        // notification nobody ever saw.
        if (!container.notificationGate.canPost()) {
            container.notificationRepo.updateStatus(notification.id, NotificationStatus.MISSED, firedAt = null)
            return
        }

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

        postToSystem(context, notification.id, androidNotification)

        container.notificationRepo.updateStatus(notification.id, NotificationStatus.FIRED, Clock.System.now())
    }

    /**
     * Lint can no longer see the POST_NOTIFICATIONS check that guards this
     * call, because it moved out of this file and behind [NotificationGate]
     * — which is the whole point: the fire path and the UI warnings have to
     * read one definition of "can we deliver", or they drift. The suppression
     * is a single statement wide so it can never come to cover a genuinely
     * unguarded call added later.
     */
    @SuppressLint("MissingPermission")
    private fun postToSystem(context: Context, notificationId: String, notification: Notification) {
        NotificationManagerCompat.from(context).notify(notificationId.hashCode(), notification)
    }

    private fun NotificationStatus.isTerminal() =
        this == NotificationStatus.CANCELLED || this == NotificationStatus.FIRED || this == NotificationStatus.MISSED

    /** Internal, not private: §17.5's instrumented test asserts on this exact key, and a
     *  duplicated literal there could drift from the one actually written. */
    internal const val KEY_APPROXIMATE_HEDGE_SHOWN = "approximate_hedge_shown"
}
