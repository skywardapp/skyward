package dev.fritze.skyward.ui.navigation

import android.net.Uri

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

    /**
     * Occurrence ids are natural keys (§6.4). None of the current formats
     * contain a `/`, but nothing guarantees a future one won't -- a raw `/`
     * would split the route into extra segments that no destination
     * matches, and `navigate` throws instead of opening anything. Encoding
     * here keeps every phenomenon's detail screen reachable regardless —
     * Navigation decodes path arguments again on the way out, so
     * [EVENT_DETAIL_ARG] still reads back as the id the source minted.
     */
    fun eventDetail(occurrenceId: String) = "event/" + Uri.encode(occurrenceId)

    const val LOCATION_EDITOR_ARG = "locationId"
    const val LOCATION_EDITOR_NEW = "settings/locations/new"
    const val LOCATION_EDITOR_EDIT = "settings/locations/edit/{$LOCATION_EDITOR_ARG}"
    fun locationEditor(locationId: String) = "settings/locations/edit/$locationId"

    const val RULE_EDITOR_ARG = "ruleId"
    const val RULE_EDITOR_NEW = "rules/new"
    const val RULE_EDITOR_EDIT = "rules/edit/{$RULE_EDITOR_ARG}"
    fun ruleEditor(ruleId: String) = "rules/edit/$ruleId"

    /** Routes that show the bottom nav bar (§13.1: Upcoming/Rules/Settings only). */
    val BOTTOM_BAR_ROUTES = setOf(UPCOMING, RULES, SETTINGS)
}
