package dev.fritze.skyward.core.format

/**
 * §4.1: the human name for an `EventSource.id`, shared rather than copied.
 * This existed once per frontend, byte-identical, under a comment asking the
 * next editor to keep it that way — same finding as [phenomenonLabel].
 *
 * Unknown ids fall through to the id itself: a source added to `:core` and
 * not yet named here should read as an unfamiliar row in Settings, not vanish
 * behind a blank label.
 */
fun sourceDisplayName(id: String): String = when (id) {
    "swpc" -> "Aurora (NOAA SWPC)"
    "jpl" -> "Comets (JPL)"
    "eonet" -> "Terrestrial events (NASA EONET)"
    "eclipse" -> "Eclipses"
    "meteors" -> "Meteor showers"
    "moon" -> "Moon events"
    "conjunctions" -> "Conjunctions"
    else -> id
}
