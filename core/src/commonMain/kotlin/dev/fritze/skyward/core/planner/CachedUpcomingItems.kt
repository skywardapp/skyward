package dev.fritze.skyward.core.planner

import dev.fritze.skyward.core.model.Occurrence
import dev.fritze.skyward.core.model.Phenomenon
import dev.fritze.skyward.core.model.SavedLocation
import dev.fritze.skyward.core.persistence.VisibilityCacheRepo
import dev.fritze.skyward.core.rules.Rule
import dev.fritze.skyward.core.visibility.VisibilityContext
import dev.fritze.skyward.core.visibility.VisibilityModel
import dev.fritze.skyward.core.visibility.VisibilityResultCache
import kotlinx.datetime.TimeZone

/**
 * §11/§9.2 step 1: [computeUpcomingItems] wrapped with a read-through
 * `visibility_cache` pass (issue #18) -- loads the cache, evaluates
 * through it, and persists whatever it computed fresh. Shared by the
 * Android and desktop Upcoming screens (`UpcomingViewModel`,
 * `rememberUpcoming`) so neither UI layer re-implements the load/wrap/
 * persist wiring around the pure [computeUpcomingItems] call; the same
 * split [ReplanCoordinator.replan] uses for the planner side.
 */
suspend fun cachedUpcomingItems(
    visibilityCacheRepo: VisibilityCacheRepo,
    occurrences: List<Occurrence>,
    locations: List<SavedLocation>,
    rules: List<Rule>,
    visibilityModels: Map<Phenomenon, VisibilityModel>,
    ctx: VisibilityContext,
    filter: UpcomingFilter,
    zone: TimeZone,
): List<UpcomingItem> {
    val cache = VisibilityResultCache(visibilityCacheRepo.getAll(), zone)
    val items = computeUpcomingItems(occurrences, locations, rules, cache.wrap(visibilityModels), ctx, filter)
    visibilityCacheRepo.upsertAll(cache.dirty)
    return items
}
