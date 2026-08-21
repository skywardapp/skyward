package dev.fritze.skyward.ui.common

import android.content.Context
import android.content.Intent
import android.provider.Settings

/**
 * Opens the system's per-app notification screen — the only place the
 * app-level notification toggle and a denied POST_NOTIFICATIONS grant can
 * both be reversed. Shared so the "notifications are blocked" warnings
 * (§10.1) and Settings › Notifications can never send the user to different
 * screens for the same problem.
 */
fun openAppNotificationSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    context.startActivity(intent)
}
