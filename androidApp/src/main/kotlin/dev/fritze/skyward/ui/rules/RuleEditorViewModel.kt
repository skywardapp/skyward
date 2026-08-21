package dev.fritze.skyward.ui.rules

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.fritze.skyward.core.model.Phenomenon
import dev.fritze.skyward.core.model.SavedLocation
import dev.fritze.skyward.core.model.TimeWindow
import dev.fritze.skyward.core.planner.Planner
import dev.fritze.skyward.core.rules.Rule
import dev.fritze.skyward.core.sources.AuroraSource
import dev.fritze.skyward.core.sources.DerivedThresholds
import dev.fritze.skyward.core.sources.RefreshRequest
import dev.fritze.skyward.core.sources.SourceSettings
import dev.fritze.skyward.core.visibility.VisibilityContext
import dev.fritze.skyward.data.AppContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

/** Backs [RuleEditorScreen] for both "add" and "edit" (existing == null means add). */
class RuleEditorViewModel(private val container: AppContainer, private val ruleId: String?) : ViewModel() {

    val locations: StateFlow<List<SavedLocation>> = container.locationRepo.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** §9.5's rule-set-wide cap (100 rules) -- the per-rule depth/node caps are checked from the draft directly via [dev.fritze.skyward.core.rules.RuleLimits]. */
    val ruleCount: StateFlow<Int> = container.ruleRepo.observeAll()
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    suspend fun load(): Rule? = ruleId?.let { container.ruleRepo.getById(it) }

    /**
     * §13.4 live preview: "matches N upcoming events" against current DB
     * occurrences, ignoring the draft's own enabled toggle so editing a
     * disabled rule still previews. `Planner.computeMatches` and
     * `AuroraSource.loadOvationGrid` do their own dispatcher switching, so
     * this runs on [Dispatchers.Default] rather than the caller's (Main).
     */
    suspend fun previewCount(draft: Rule): Int = withContext(Dispatchers.Default) {
        val occurrences = container.occurrenceRepo.getAll()
        val locs = container.locationRepo.getAll()
        val ctx = VisibilityContext(now = Clock.System.now(), ovationGrid = AuroraSource.loadOvationGrid(container.sourceStateRepo))
        val matches = Planner.computeMatches(occurrences, locs, listOf(draft.copy(enabled = true)), container.visibilityModels, ctx)
        matches.distinctBy { it.occ.id }.size
    }

    /** True if at least one selected phenomenon is COMPUTED (sources are pure functions of time, so a past horizon can be queried). */
    fun hasComputedPhenomenon(phenomena: Set<Phenomenon>): Boolean =
        phenomena.isNotEmpty() && container.computedSources.any { source -> source.phenomena.any { it in phenomena } }

    /**
     * §13.4: "For COMPUTED phenomena the preview additionally enumerates the
     * past 2 years on demand ... call them with a past horizon into a
     * temporary in-memory list, never into the DB." Returns null when no
     * selected phenomenon is COMPUTED (nothing to enumerate).
     */
    suspend fun pastMatchCount(draft: Rule): Int? = withContext(Dispatchers.Default) {
        val relevantSources = container.computedSources.filter { source -> source.phenomena.any { it in draft.phenomena } }
        if (relevantSources.isEmpty()) return@withContext null

        val now = Clock.System.now()
        val locs = container.locationRepo.getAll()
        val request = RefreshRequest(
            now = now,
            horizon = TimeWindow(now - PAST_HORIZON, now),
            locations = locs,
            state = emptyMap(),
            settings = SourceSettings(),
            derivedThresholds = DerivedThresholds(minKpOfInterest = null, maxCometMag = null, maxTravelKm = null),
        )
        val pastOccurrences = relevantSources.flatMap { it.refresh(request).occurrences }
        val ctx = VisibilityContext(now = now, ovationGrid = null) // COMPUTED phenomena never read the aurora grid
        val matches = Planner.computeMatches(pastOccurrences, locs, listOf(draft.copy(enabled = true)), container.visibilityModels, ctx)
        matches.distinctBy { it.occ.id }.size
    }

    private val _error = MutableStateFlow<String?>(null)

    /**
     * Why the last save or delete didn't happen, or null. Both used to launch
     * and forget: a failed write left the editor sitting there looking like
     * nothing had been asked of it, and `onDone()` was never reached, so the
     * screen didn't close either -- the user saw a button that did nothing.
     */
    val error: StateFlow<String?> = _error.asStateFlow()

    fun save(rule: Rule, onDone: () -> Unit) {
        writeThenReplan(
            onDone,
            whenWriteFails = "Couldn't save this rule to this device — try again.",
            whenReplanFails = "The rule was saved, but its reminders couldn't be rescheduled — try again.",
        ) { container.ruleRepo.upsert(rule) }
    }

    fun delete(rule: Rule, onDone: () -> Unit) {
        writeThenReplan(
            onDone,
            whenWriteFails = "Couldn't delete this rule — try again.",
            whenReplanFails = "The rule was deleted, but its reminders couldn't be cancelled — try again.",
        ) { container.ruleRepo.delete(rule.id) }
    }

    /**
     * Runs [write], then replans, and leaves the editor only once both have
     * succeeded. The two failures are reported apart because they leave
     * different states behind: a failed write changed nothing, while a failed
     * replan means the rule store is right and the OS alarms are not.
     */
    private fun writeThenReplan(
        onDone: () -> Unit,
        whenWriteFails: String,
        whenReplanFails: String,
        write: suspend () -> Unit,
    ) {
        viewModelScope.launch {
            _error.value = null
            try {
                write()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = whenWriteFails
                return@launch
            }
            try {
                container.replanAndSync()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = whenReplanFails
                return@launch
            }
            onDone()
        }
    }

    private companion object {
        val PAST_HORIZON = (365 * 2).days
    }
}
