package dev.fritze.skyward.alarm

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import dev.fritze.skyward.MainActivity

/**
 * §10.2's fired-reminder tap target: what a posted notification opens.
 *
 * Without a `contentIntent` a tap does nothing at all — and `setAutoCancel`,
 * which only ever fires *through* a content intent, cannot even dismiss the
 * notification. The desktop side has always raised its window on the relevant
 * detail view (§10.3); this is the Android half of the same promise.
 *
 * The occurrence id travels in the intent's `action`, not in an extra, for
 * the same reason [notificationPendingIntent] encodes its id there:
 * `PendingIntent` identity is (action, data, type, component, categories) and
 * ignores extras, so two reminders differing only in an extra would be the
 * *same* PendingIntent — and `FLAG_UPDATE_CURRENT` would silently rewrite the
 * first one's target to the second's, sending both taps to one occurrence.
 */
internal const val OPEN_EVENT_ACTION_PREFIX = "dev.fritze.skyward.action.OPEN_EVENT:"

internal fun openEventAction(occurrenceId: String): String = OPEN_EVENT_ACTION_PREFIX + occurrenceId

/**
 * The occurrence [MainActivity] was launched to show, or null for an ordinary
 * launch (launcher icon, task switcher) — including a reminder whose
 * occurrence row had already been withdrawn, which opens the app plainly
 * rather than a detail screen with nothing behind it.
 */
internal fun occurrenceIdFromLaunchAction(action: String?): String? =
    action?.takeIf { it.startsWith(OPEN_EVENT_ACTION_PREFIX) }
        ?.substring(OPEN_EVENT_ACTION_PREFIX.length)
        ?.takeIf { it.isNotEmpty() }

/** FLAG_IMMUTABLE, as API 31+ requires of every PendingIntent we hand the system. */
internal fun openEventPendingIntent(context: Context, occurrenceId: String?): PendingIntent {
    val intent = Intent(context, MainActivity::class.java).apply {
        if (occurrenceId != null) action = openEventAction(occurrenceId)
        // MainActivity is launchMode="singleTop", so CLEAR_TOP delivers this to
        // the running instance via onNewIntent instead of stacking a second
        // copy of the whole app on top of the one the user already has open.
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }
    return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
}
