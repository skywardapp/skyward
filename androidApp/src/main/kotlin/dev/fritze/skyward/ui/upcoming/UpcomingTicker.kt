package dev.fritze.skyward.ui.upcoming

import dev.fritze.skyward.core.model.AuroraForecastKind
import dev.fritze.skyward.core.model.AuroraPayload
import dev.fritze.skyward.core.model.Occurrence
import dev.fritze.skyward.core.model.Phenomenon
import dev.fritze.skyward.core.planner.UpcomingItem
import dev.fritze.skyward.core.planner.computeUpcomingItems
import dev.fritze.skyward.core.visibility.OvationGrid
import dev.fritze.skyward.core.visibility.VisibilityContext
import dev.fritze.skyward.core.visibility.VisibilityModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * §13.2's screen state is a function of *now* as much as of the database: a
 * card's countdown reads "Tomorrow" until the moment it should read "Today",
 * and the aurora banner is only true until the nowcast behind it expires
 * (§7.3, `expiresAt = fetchedAt + 2h`). The repository, filter and
 * pull-to-refresh flows never emit at those moments, so the state they
 * produced sits on screen past the boundary — an expired nowcast stays pinned
 * until something unrelated happens to emit.
 *
 * This file closes that gap by making time itself an input: the state is
 * recomputed at each instant where a time-derived value can change, and the
 * flow sleeps in between. Deliberately not a fixed-interval poll — a screen
 * with nothing due next simply stops ticking, and a screen with something due
 * in 40 minutes wakes once, not 40 times.
 */

/**
 * Re-emits [UpcomingUiState] for one fixed set of inputs as time passes.
 *
 * The caller supplies everything that changes for non-time reasons; a new
 * repository/filter/refresh emission cancels this flow and starts a fresh one
 * (see [UpcomingViewModel.uiState]), so nothing here has to watch for that.
 * The flow completes once no further boundary exists — an all-past list has
 * nothing left to invalidate.
 */
internal fun upcomingStatesOverTime(
    base: UpcomingBaseState,
    currentKp: Double?,
    ovationGrid: OvationGrid?,
    visibilityModels: Map<Phenomenon, VisibilityModel>,
    clock: Clock,
): Flow<UpcomingUiState> = flow {
    while (true) {
        val now = clock.now()
        val ctx = VisibilityContext(now = now, ovationGrid = ovationGrid)
        val items = computeUpcomingItems(
            base.occurrences, base.locations, base.rules, visibilityModels, ctx, base.filter,
        )
        emit(
            UpcomingUiState(
                items = items,
                auroraBanner = activeAuroraBanner(
                    occurrences = base.occurrences,
                    locations = base.locations,
                    visibilityModels = visibilityModels,
                    ctx = ctx,
                    currentKp = currentKp,
                ),
                filter = base.filter,
                isLoading = false,
                isRefreshing = base.isRefreshing,
                now = now,
            ),
        )
        val next = nextTimeBoundary(now, items, base.occurrences) ?: return@flow
        // The floor keeps a boundary that is already upon us (or a clock that
        // stepped backwards) from spinning: at worst the state lands a second
        // late, which nothing on this screen renders finer than.
        delay((next - now).coerceAtLeast(MIN_TICK))
    }
}

/**
 * The next instant at which some time-derived part of the Upcoming screen can
 * change — a card countdown rolling over, or the aurora banner appearing or
 * expiring. Null when nothing is left to invalidate.
 */
internal fun nextTimeBoundary(
    now: Instant,
    items: List<UpcomingItem>,
    occurrences: List<Occurrence>,
): Instant? {
    val candidates = buildList {
        for (item in items) {
            countdownChangeAfter(countdownAnchor(item.occurrence), now)?.let(::add)
        }
        for (occurrence in occurrences) {
            addAll(auroraBannerBoundaries(occurrence))
        }
    }
    return candidates.filter { it > now }.minOrNull()
}

/** §13.2's countdown counts down to the peak, or to the window opening. */
internal fun countdownAnchor(occurrence: Occurrence): Instant =
    occurrence.peakTime ?: occurrence.window.start

/**
 * §13.2's card countdown ("in 3 weeks"). Lives next to [countdownChangeAfter]
 * because the two have to agree about where its buckets end.
 */
internal fun countdownText(target: Instant, now: Instant): String {
    val delta = target - now
    if (delta < Duration.ZERO) return "Past"
    val days = delta.inWholeDays
    return when {
        days == 0L -> "Today"
        days == 1L -> "Tomorrow"
        days < 7 -> "In $days days"
        days < 60 -> "In ${days / 7} week${if (days / 7 == 1L) "" else "s"}"
        else -> "In ${days / 30} month${if (days / 30 == 1L) "" else "s"}"
    }
}

/**
 * The first instant after [now] at which [countdownText] for [target] *can*
 * read differently: when the whole-day count it buckets rolls over. Null once
 * the countdown reads "Past", which never changes again.
 *
 * An upper bound on staleness rather than an exact change point — the coarse
 * buckets often survive a rollover ("in 8 weeks" spans seven of them), so this
 * can wake the screen for a recomputation that changes nothing. That costs one
 * recomputation per card per day; being wrong the other way would leave a
 * stale countdown on screen, which is the whole bug. `UpcomingTickerTest`
 * pins the direction that matters: the text at the returned instant minus a
 * tick still equals the text at [now].
 */
internal fun countdownChangeAfter(target: Instant, now: Instant): Instant? {
    val remaining = target - now
    if (remaining < Duration.ZERO) return null
    // At `target - wholeDays` the count still reads `wholeDays` (the division
    // truncates), so the change lands one instant later — without the epsilon
    // a tick that arrives exactly on a rollover would compute itself as its
    // own next boundary, drop it as "not in the future", and stop ticking.
    return target - remaining.inWholeDays.days + BOUNDARY_EPSILON
}

/**
 * The instants at which [activeAuroraBanner]'s verdict on this occurrence can
 * flip: its window opening, and the nowcast expiring — `expiresAt`, or
 * `window.end` for a row that carries no explicit expiry. Kept deliberately in
 * lockstep with the predicate there.
 */
private fun auroraBannerBoundaries(occurrence: Occurrence): List<Instant> {
    if (occurrence.phenomenon != Phenomenon.AURORA) return emptyList()
    val payload = occurrence.payload as? AuroraPayload ?: return emptyList()
    if (payload.forecastKind != AuroraForecastKind.NOWCAST) return emptyList()
    return listOf(occurrence.window.start, occurrence.expiresAt ?: occurrence.window.end)
}

private val MIN_TICK = 1.seconds
private val BOUNDARY_EPSILON = 1.nanoseconds
