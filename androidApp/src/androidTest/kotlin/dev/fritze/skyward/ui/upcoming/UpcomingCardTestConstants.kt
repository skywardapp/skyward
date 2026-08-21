package dev.fritze.skyward.ui.upcoming

/**
 * The two permission cards Upcoming can show are asserted from both
 * directions -- one suite proves each appears, the other proves it is
 * suppressed -- so the titles and the dismissal key live here rather than
 * being copied into each suite, where they could silently drift out of step
 * with the screen and with each other.
 */
internal const val EXACT_ALARM_CARD_TITLE = "Exact alarms are off"
internal const val NOTIFICATIONS_BLOCKED_CARD_TITLE = "Notifications are blocked"
internal const val EXACT_ALARM_DISMISSED_VERSION_KEY = "exact_alarm_card_dismissed_version"
