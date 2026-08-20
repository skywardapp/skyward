package dev.fritze.skyward.desktop.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import dev.fritze.skyward.core.planner.UpcomingFilter
import dev.fritze.skyward.core.planner.UpcomingItem
import dev.fritze.skyward.core.planner.computeUpcomingItems
import dev.fritze.skyward.core.visibility.VisibilityResultCache
import dev.fritze.skyward.desktop.ui.DesktopAppState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class UpcomingState(val items: List<UpcomingItem>, val isLoading: Boolean)

/**
 * §13.2's `computeUpcomingItems`, driven from the desktop's shared flows.
 * The same pure core function the Android Upcoming screen uses — the only
 * thing that differs is where the inputs come from and where the output is
 * drawn (§4.1).
 *
 * Evaluated off the UI thread (§4.3: "astronomy computations run on
 * `Dispatchers.Default` ... must never run on the main thread"). A full pass
 * over a three-year horizon runs a rise/set search per (occurrence,
 * location), which is emphatically not frame-budget work.
 */
@Composable
fun rememberUpcoming(state: DesktopAppState, filter: UpcomingFilter): UpcomingState {
    val occurrences by state.occurrences.collectAsState()
    val locations by state.locations.collectAsState()
    val rules by state.allRules.collectAsState()
    val now by state.tick.collectAsState()
    val grid by state.ovationGrid.collectAsState()

    // Keyed on a coarsened clock, not the 60-second tick: nothing this pass
    // computes — rise/set windows, quality bands, rule matches — moves
    // perceptibly inside five minutes, and re-running a three-year horizon
    // every minute is the difference between an idle app and a busy one.
    // Countdowns still read the fine-grained tick directly where they render.
    val recomputeAt = now.epochSeconds / RECOMPUTE_BUCKET_SECONDS

    val result by produceState(
        initialValue = UpcomingState(emptyList(), isLoading = true),
        occurrences, locations, rules, filter, recomputeAt, grid,
    ) {
        value = value.copy(isLoading = true)
        val items = withContext(Dispatchers.Default) {
            // §11/§9.2 step 1: read-through visibility_cache, same as the
            // replan path -- a three-year horizon recomputed on every
            // occurrence/location/rule/tick emission is not frame-budget
            // work (issue #18).
            val cache = VisibilityResultCache(state.container.visibilityCacheRepo.getAll(), state.zone)
            val computed = computeUpcomingItems(
                occurrences = occurrences,
                locations = locations,
                rules = rules,
                visibilityModels = cache.wrap(state.container.visibilityModels),
                ctx = state.visibilityContext(now),
                filter = filter,
            )
            state.container.visibilityCacheRepo.upsertAll(cache.dirty)
            computed
        }
        value = UpcomingState(items, isLoading = false)
    }
    return result
}

private const val RECOMPUTE_BUCKET_SECONDS = 300L
