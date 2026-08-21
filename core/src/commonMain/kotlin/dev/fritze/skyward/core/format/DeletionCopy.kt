package dev.fritze.skyward.core.format

import dev.fritze.skyward.core.rules.LocationDeletionImpact
import dev.fritze.skyward.core.rules.Rule

/** Title and body for a destructive-action confirmation, rendered by whichever platform's dialog. */
data class ConfirmCopy(val title: String, val body: String)

/**
 * Deleting a rule cancels its reminders and cannot be undone -- both
 * frontends say the same thing about it, because the consequence is the same
 * on both. Desktop used to delete on a single click with no dialog at all.
 */
fun deleteRuleConfirmation(ruleName: String): ConfirmCopy = ConfirmCopy(
    title = "Delete this rule?",
    body = "\"${ruleName.ifBlank { "This rule" }}\" will stop matching events and its reminders will be cancelled.",
)

/**
 * Deleting a saved location, spelling out what it does to the rules that name
 * it (§9.1's `locationIds`).
 *
 * Listing the affected rules by name is the point: the damage is invisible
 * otherwise -- a rule that named only this place goes on sitting in the list
 * looking healthy while matching nothing.
 */
fun deleteLocationConfirmation(locationName: String, impact: LocationDeletionImpact): ConfirmCopy {
    val name = locationName.ifBlank { "This location" }
    val sentences = mutableListOf(
        "\"$name\" will be removed and any reminders for it cancelled.",
    )
    if (impact.stranded.size == 1) {
        sentences += "One rule names only \"$name\" and will be turned off until you give it another location: " +
            "${listRuleNames(impact.stranded)}."
    } else if (impact.stranded.size > 1) {
        sentences += "${impact.stranded.size} rules name only \"$name\" and will be turned off until you give them " +
            "another location: ${listRuleNames(impact.stranded)}."
    }
    if (impact.narrowed.size == 1) {
        sentences += "One other rule keeps working from its remaining locations: ${listRuleNames(impact.narrowed)}."
    } else if (impact.narrowed.size > 1) {
        sentences += "${impact.narrowed.size} other rules keep working from their remaining locations: " +
            "${listRuleNames(impact.narrowed)}."
    }
    return ConfirmCopy(title = "Delete this location?", body = sentences.joinToString(" "))
}

/** How many rule names a dialog lists before summarising the rest -- past this it stops being readable. */
private const val MAX_LISTED_RULES = 5

private fun listRuleNames(rules: List<Rule>): String {
    val names = rules.map { "\"${it.name.ifBlank { "Untitled rule" }}\"" }
    if (names.size <= MAX_LISTED_RULES) return names.joinToString(", ")
    val shown = names.take(MAX_LISTED_RULES)
    return shown.joinToString(", ") + " and ${names.size - MAX_LISTED_RULES} more"
}
