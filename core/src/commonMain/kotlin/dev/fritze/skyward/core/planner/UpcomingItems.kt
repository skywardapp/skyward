package dev.fritze.skyward.core.planner

import dev.fritze.skyward.core.model.Occurrence
import dev.fritze.skyward.core.model.Phenomenon
import dev.fritze.skyward.core.model.Quality
import dev.fritze.skyward.core.model.SavedLocation
import dev.fritze.skyward.core.model.VisibilityResult
import dev.fritze.skyward.core.model.hasExpiredAt
import dev.fritze.skyward.core.rules.Rule
import dev.fritze.skyward.core.visibility.VisibilityContext
import dev.fritze.skyward.core.visibility.VisibilityModel

/** §13.2's [Matched]/[All] toggle plus optional per-phenomenon narrowing. */
enum class UpcomingScope { MATCHED, ALL }
data class UpcomingFilter(val scope: UpcomingScope = UpcomingScope.MATCHED, val phenomena: Set<Phenomenon> = emptySet())

/** One Upcoming-screen card's worth of precomputed display data (§13.2). */
data class UpcomingItem(
    val occurrence: Occurrence,
    val bestLocation: SavedLocation,
    val bestVisres: VisibilityResult,
    val matchedRuleNames: List<String>,
)

/**
 * §13.2: "occurrences within horizon that match >= 1 enabled rule OR are
 * 'notable anyway' (EXCELLENT quality at any saved location)" is what backs
 * the [UpcomingScope.MATCHED] chip (the app's own always-on "notable" rule,
 * in effect); [UpcomingScope.ALL] drops that restriction entirely. Hidden
 * rules (mutes, one-off reminders, §13.3) don't count toward "matched" here
 * -- they're not something a user would recognize as "why is this here".
 */
fun computeUpcomingItems(
    occurrences: List<Occurrence>,
    locations: List<SavedLocation>,
    rules: List<Rule>,
    visibilityModels: Map<Phenomenon, VisibilityModel>,
    ctx: VisibilityContext,
    filter: UpcomingFilter,
): List<UpcomingItem> {
    if (locations.isEmpty()) return emptyList()

    // §5: an expired forecast is last-known data, not something to present
    // as upcoming -- in either scope. [UpcomingScope.ALL] drops the
    // "matches a rule" restriction, not the "this data is still current"
    // one.
    val current = occurrences.filterNot { it.hasExpiredAt(ctx.now) }

    val visibleRules = rules.filter { it.enabled && !it.hidden }
    val matches = Planner.computeMatches(current, locations, visibleRules, visibilityModels, ctx)
    val matchedRuleNamesByOccurrence = matches.groupBy({ it.occ.id }) { it.rule.name }.mapValues { it.value.distinct() }

    val items = current.mapNotNull { occ ->
        val model = visibilityModels[occ.phenomenon] ?: return@mapNotNull null
        val results = locations.associateWith { loc -> model.evaluate(occ, loc, ctx) }
        val ruleNames = matchedRuleNamesByOccurrence[occ.id].orEmpty()
        val notable = results.values.any { it.quality == Quality.EXCELLENT }
        if (filter.scope == UpcomingScope.MATCHED && ruleNames.isEmpty() && !notable) return@mapNotNull null
        if (filter.phenomena.isNotEmpty() && occ.phenomenon !in filter.phenomena) return@mapNotNull null

        val (bestLocation, bestVisres) = results.entries.maxBy { it.value.quality }.toPair()
        UpcomingItem(occ, bestLocation, bestVisres, ruleNames)
    }

    return items.sortedBy { it.occurrence.peakTime ?: it.occurrence.window.start }
}
