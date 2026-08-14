package dev.fritze.skyward.alarm

import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/**
 * Shared PendingIntent identity for a [dev.fritze.skyward.core.model.PlannedNotification]
 * id. `PendingIntent` equality (and thus what `AlarmManager.cancel` matches)
 * is based on (action, data, type, component, categories) -- NOT extras --
 * so the id is encoded as the intent's `action` rather than an extra.
 */
internal const val ACTION_PREFIX = "dev.fritze.skyward.action.FIRE_NOTIFICATION:"

internal fun notificationPendingIntent(context: Context, notificationId: String): PendingIntent {
    val intent = Intent(context, NotificationAlarmReceiver::class.java).apply {
        action = ACTION_PREFIX + notificationId
    }
    return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
}
