package dev.fritze.skyward.alarm

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * §10.1: whether the OS will actually show a notification we post.
 *
 * Deliberately separate from [AlarmScheduler]: the two degrade
 * independently — a device can hold the exact-alarm permission and still
 * have every notification blocked — and this one is the fatal failure,
 * because a blocked notification is not late, it is gone. Both the fire
 * path ([NotificationPoster]) and the warning cards in the UI read it
 * through this interface so they can never disagree about whether
 * reminders are reaching the user.
 *
 * It is also §17.5's test seam. Neither the runtime grant nor the app-level
 * notification toggle can be flipped from inside an instrumented test, so
 * tests substitute a fake, exactly as they do for [AlarmScheduler].
 */
fun interface NotificationGate {
    fun canPost(): Boolean
}

/** The real check, against the runtime permission and the app-level toggle. */
class AndroidNotificationGate(private val context: Context) : NotificationGate {

    override fun canPost(): Boolean {
        // Two checks, because they are two different denials. POST_NOTIFICATIONS
        // only exists from API 33 and covers the runtime prompt; the app-level
        // toggle in system settings exists on every supported API level, is
        // invisible to checkSelfPermission, and silences the app just as
        // completely. Onboarding's "Not now" trips the first; a user turning
        // Skyward off months later trips the second.
        val permissionGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        // Per-channel importance is deliberately *not* consulted: §10.2 gives
        // each phenomenon its own channel precisely so a user can silence
        // meteor showers and keep eclipses loud. A muted channel is a choice
        // the user made about one phenomenon, not a delivery failure to warn
        // about, so it must not raise the app-wide "notifications are blocked"
        // alarm.
        return permissionGranted && NotificationManagerCompat.from(context).areNotificationsEnabled()
    }
}
