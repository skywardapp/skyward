package dev.fritze.skyward.core.rules

import kotlin.time.Instant

/**
 * What deleting a saved location would do to the rules that name it.
 *
 * §9.1's `Rule.locationIds` addresses locations by id, with `null` meaning
 * "all saved locations". Nothing in the schema ties those ids to
 * `saved_location` rows, so deleting a location leaves the references behind:
 * a rule that named only that location keeps a one-element list that matches
 * no location at all, and [RuleEngine] then reports "no match" for every
 * occurrence, forever, with nothing on screen to explain why.
 */
data class LocationDeletionImpact(
    /** Rules that named this location and at least one other -- they keep working, from fewer places. */
    val narrowed: List<Rule>,
    /** Rules that named only this location -- after the delete they have nowhere left to match. */
    val stranded: List<Rule>,
) {
    val isEmpty: Boolean get() = narrowed.isEmpty() && stranded.isEmpty()
}

/**
 * Which of [rules] name [locationId]. Rules with `locationIds == null` are
 * untouched by definition: they mean "all saved locations", which is still a
 * well-defined set with one fewer member.
 */
fun locationDeletionImpact(locationId: String, rules: List<Rule>): LocationDeletionImpact {
    val referencing = rules.filter { locationId in (it.locationIds ?: emptyList()) }
    return LocationDeletionImpact(
        narrowed = referencing.filter { (it.locationIds ?: emptyList()).size > 1 },
        stranded = referencing.filter { (it.locationIds ?: emptyList()).size == 1 },
    )
}

/**
 * The rules that have to change when [locationId] is deleted, already
 * rewritten -- only those that actually differ, so callers can upsert the
 * result without touching every row's `modifiedAt` (which §12's sync uses to
 * resolve conflicts).
 *
 * A stranded rule is disabled rather than widened to `locationIds = null`.
 * Widening looks tidier and is wrong: it would silently start firing
 * reminders for places the user never picked. Its now-empty selection is left
 * empty on purpose too -- that is exactly the state `RuleEditorScreen` refuses
 * to save ("pick at least one location, or switch to all saved locations"),
 * so opening the rule says what to fix.
 */
fun rulesAfterLocationDeletion(locationId: String, rules: List<Rule>, now: Instant): List<Rule> {
    val impact = locationDeletionImpact(locationId, rules)
    return impact.narrowed.map { rule ->
        rule.copy(locationIds = rule.locationIds.orEmpty() - locationId, modifiedAt = now)
    } + impact.stranded.map { rule ->
        rule.copy(locationIds = emptyList(), enabled = false, modifiedAt = now)
    }
}
