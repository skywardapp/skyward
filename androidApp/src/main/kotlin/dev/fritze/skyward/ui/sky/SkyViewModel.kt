package dev.fritze.skyward.ui.sky

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.fritze.skyward.core.model.Occurrence
import dev.fritze.skyward.core.model.SavedLocation
import dev.fritze.skyward.core.rules.Rule
import dev.fritze.skyward.core.sources.AuroraSource
import dev.fritze.skyward.core.sources.KpEstimate
import dev.fritze.skyward.core.sources.KpNowcast
import dev.fritze.skyward.core.visibility.OvationGrid
import dev.fritze.skyward.data.AppContainer
import dev.fritze.skyward.util.runCatchingCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Everything the four drawn views (§14.1-§14.4) read, in one place.
 *
 * The desktop equivalent is `DesktopAppState`, which is a plain reactive
 * object rather than an androidx `ViewModel`, so it cannot be shared (ADR
 * 0022). One view-model rather than four because the tabs sit behind a
 * single `Routes.SKY` destination and overlap heavily — the map, timeline
 * and sky chart all read occurrences; the map and aurora dashboard both
 * read the OVATION grid — and four would each hold their own copy of the
 * same query.
 */
data class SkyUiState(
    val occurrences: List<Occurrence> = emptyList(),
    val locations: List<SavedLocation> = emptyList(),
    val rules: List<Rule> = emptyList(),
    val ovationGrid: OvationGrid? = null,
    val currentKp: KpEstimate? = null,
    val isLoading: Boolean = true,
)

class SkyViewModel(
    private val container: AppContainer,
    private val clock: Clock = Clock.System,
) : ViewModel() {

    /**
     * §7.3.2 rewrites the grid only as part of a source run, which also
     * emits new occurrences — so the grid is reloaded whenever those change
     * rather than polled.
     */
    private val ovationGrid = MutableStateFlow<OvationGrid?>(null)
    private val currentKp = MutableStateFlow<KpEstimate?>(null)

    /** §5: the one place an Instant becomes a wall-clock time for these views. */
    val zone: TimeZone = TimeZone.currentSystemDefault()

    init {
        viewModelScope.launch { refreshDerivedState() }
    }

    val uiState: StateFlow<SkyUiState> = combine(
        container.occurrenceRepo.observeAll(),
        container.locationRepo.observeAll(),
        container.ruleRepo.observeAll(),
        ovationGrid,
        currentKp,
    ) { occurrences, locations, rules, grid, kp ->
        SkyUiState(
            occurrences = occurrences,
            locations = locations,
            rules = rules,
            ovationGrid = grid,
            currentKp = kp,
            isLoading = false,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SkyUiState())

    fun now(): Instant = clock.now()

    /**
     * §14.4: "Refresh: dashboard-open forces active polling tier (§7.3.2)."
     * Called when the Aurora tab is opened, not on every recomposition.
     */
    fun refreshAurora() {
        viewModelScope.launch {
            if (container.settingsRepo.isSourceEnabled(SWPC_SOURCE_ID)) {
                runCatchingCancellable {
                    container.sourceRunner.runDue(clock.now(), force = setOf(SWPC_SOURCE_ID))
                }
            }
            refreshDerivedState()
        }
    }

    private suspend fun refreshDerivedState() {
        ovationGrid.value = withContext(Dispatchers.Default) {
            AuroraSource.loadOvationGrid(container.sourceStateRepo)
        }
        // A source the user turned off is not a failure to report, and a
        // failed fetch keeps the last good estimate rather than blanking the
        // gauge — a Kp from twenty minutes ago is still the best thing known.
        // Same contract as UpcomingViewModel's banner.
        if (!container.settingsRepo.isSourceEnabled(SWPC_SOURCE_ID)) {
            currentKp.value = null
            return
        }
        runCatchingCancellable { KpNowcast.fetchLatest() }.getOrNull()?.let { currentKp.value = it }
    }

    private companion object {
        const val SWPC_SOURCE_ID = "swpc"
    }
}
