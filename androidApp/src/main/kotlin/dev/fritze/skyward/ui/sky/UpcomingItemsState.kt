package dev.fritze.skyward.ui.sky

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import dev.fritze.skyward.core.planner.UpcomingFilter
import dev.fritze.skyward.core.planner.UpcomingItem
import dev.fritze.skyward.core.planner.cachedUpcomingItems
import dev.fritze.skyward.core.visibility.VisibilityContext
import dev.fritze.skyward.data.AppContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

data class UpcomingItemsState(val items: List<UpcomingItem>, val isLoading: Boolean)

/**
 * §13.2's `cachedUpcomingItems`, driven from [SkyUiState].
 *
 * The desktop has the same helper over its own flows (`rememberUpcoming`);
 * both call the one pure core function, so the timeline and sky chart on
 * either platform agree by construction rather than by review (P2, §4.1).
 *
 * Evaluated off the UI thread (§4.3: astronomy computations "must never run
 * on the main thread") — a full pass over a three-year horizon runs a
 * rise/set search per (occurrence, location), which is not frame-budget
 * work.
 */
@Composable
fun rememberUpcomingItems(
    container: AppContainer,
    state: SkyUiState,
    now: Instant,
    zone: TimeZone,
    filter: UpcomingFilter,
): UpcomingItemsState {
    // Keyed on a coarsened clock, not a live tick: nothing this pass computes
    // — rise/set windows, quality bands, rule matches — moves perceptibly
    // inside five minutes, and re-running a three-year horizon every minute
    // is the difference between an idle app and a busy one.
    val recomputeAt = now.epochSeconds / RECOMPUTE_BUCKET_SECONDS

    val result by produceState(
        initialValue = UpcomingItemsState(emptyList(), isLoading = true),
        state.occurrences, state.locations, state.rules, filter, recomputeAt, state.ovationGrid,
    ) {
        value = value.copy(isLoading = true)
        val items = withContext(Dispatchers.Default) {
            cachedUpcomingItems(
                container.visibilityCacheRepo,
                state.occurrences,
                state.locations,
                state.rules,
                container.visibilityModels,
                VisibilityContext(now, state.ovationGrid),
                filter,
                zone,
            )
        }
        value = UpcomingItemsState(items, isLoading = false)
    }
    return result
}

private const val RECOMPUTE_BUCKET_SECONDS = 300L

/**
 * The instant the drawn views are computed for, advanced at each recompute
 * boundary.
 *
 * A plain `remember { now() }` freezes the clock for as long as the screen
 * stays composed, so [rememberUpcomingItems] would never see a new
 * [RECOMPUTE_BUCKET_SECONDS] bucket and the aurora dashboard's forecast slots
 * and darkness windows would go stale on a screen left open.
 *
 * It sleeps to the next boundary rather than polling on an interval, in the
 * same spirit as `upcomingStatesOverTime`: one wake per bucket, not one per
 * tick. Aligning to the boundary also means the value only ever changes when
 * `recomputeAt` actually would.
 */
@Composable
fun rememberChartNow(now: () -> Instant): State<Instant> = produceState(now()) {
    while (true) {
        val current = now()
        value = current
        val nextBoundary = (current.epochSeconds / RECOMPUTE_BUCKET_SECONDS + 1) * RECOMPUTE_BUCKET_SECONDS
        delay((nextBoundary - current.epochSeconds).seconds)
    }
}
