package dev.fritze.skyward.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.fritze.skyward.SkywardApplication
import kotlinx.coroutines.launch

/** §10.2: the exact-path receiver — `AlarmManager` wakes this, it reads the payload from DB by id and posts. */
class NotificationAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val notificationId = intent.action?.removePrefix(ACTION_PREFIX) ?: return
        val container = (context.applicationContext as SkywardApplication).container
        val pendingResult = goAsync()
        container.applicationScope.launch {
            try {
                NotificationPoster.postNotificationFor(context, container, notificationId)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
