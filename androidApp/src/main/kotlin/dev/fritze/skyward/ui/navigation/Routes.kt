package dev.fritze.skyward.ui.navigation

/** §13.1's navigation map. */
object Routes {
    const val ONBOARDING = "onboarding"
    const val UPCOMING = "upcoming"
    const val RULES = "rules"
    const val SETTINGS = "settings"
    const val LOCATIONS = "settings/locations"
    const val NOTIFICATIONS_SETTINGS = "settings/notifications"
    const val SOURCES = "settings/sources"
    const val SYNC = "settings/sync"
    const val ABOUT = "settings/about"

    const val EVENT_DETAIL_ARG = "occurrenceId"
    const val EVENT_DETAIL = "event/{$EVENT_DETAIL_ARG}"
    fun eventDetail(occurrenceId: String) = "event/$occurrenceId"

    const val LOCATION_EDITOR_ARG = "locationId"
    const val LOCATION_EDITOR_NEW = "settings/locations/new"
    const val LOCATION_EDITOR_EDIT = "settings/locations/edit/{$LOCATION_EDITOR_ARG}"
    fun locationEditor(locationId: String) = "settings/locations/edit/$locationId"

    /** Routes that show the bottom nav bar (§13.1: Upcoming/Rules/Settings only). */
    val BOTTOM_BAR_ROUTES = setOf(UPCOMING, RULES, SETTINGS)
}
