package dev.fritze.skyward.ui.upcoming

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.fritze.skyward.core.model.AuroraForecastKind
import dev.fritze.skyward.core.model.AuroraPayload
import dev.fritze.skyward.core.model.LocalDetails
import dev.fritze.skyward.core.model.Occurrence
import dev.fritze.skyward.core.model.Phenomenon
import dev.fritze.skyward.core.model.SavedLocation
import dev.fritze.skyward.core.planner.UpcomingFilter
import dev.fritze.skyward.core.planner.UpcomingItem
import dev.fritze.skyward.core.planner.UpcomingScope
import dev.fritze.skyward.core.rules.Rule
import dev.fritze.skyward.core.sources.AuroraSource
import dev.fritze.skyward.core.sources.EventSource
import dev.fritze.skyward.core.sources.KpEstimate
import dev.fritze.skyward.core.sources.KpNowcast
import dev.fritze.skyward.core.visibility.VisibilityContext
import dev.fritze.skyward.core.visibility.VisibilityModel
import dev.fritze.skyward.core.visibility.VisibilityResultCache
import dev.fritze.skyward.data.AppContainer
import dev.fritze.skyward.util.runCatchingCancellable
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlin.time.Clock
import kotlin.time.Instant

data class UpcomingUiState(
    val items: List<UpcomingItem> = emptyList(),
    val auroraBanner: AuroraBannerUiState? = null,
    val filter: UpcomingFilter = UpcomingFilter(),
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    /**
     * The instant this state was computed for. Carried into the state rather
     * than read at render time so that a card's countdown (§13.2) is part of
     * what recomposition compares — see UpcomingTicker.kt.
     */
    val now: Instant,
)

data class AuroraBannerUiState(
    val occurrenceId: String,
    val locationName: String,
    val ovationProbabilityPercent: Int?,
    val currentKp: Double?,
    val issuedAt: Instant,
    val darknessStart: Instant?,
)

/** Holds the pieces `combine` in the view-model has no typed overload for six flows of. */
internal data class UpcomingBaseState(
    val occurrences: List<Occurrence>,
    val locations: List<SavedLocation>,
    val rules: List<Rule>,
    val filter: UpcomingFilter,
    val isRefreshing: Boolean,
)

/**
 * §13.2's core view-model — combines DB state reactively, delegates the
 * actual selection logic to `core`'s pure `computeUpcomingItems`.
 */
class UpcomingViewModel(
    private val container: AppContainer,
    // Injected so the time-boundary behaviour (UpcomingTicker.kt) can be
    // tested against virtual time instead of the wall clock.
    private val clock: Clock = Clock.System,
) : ViewModel() {
    private val filter = MutableStateFlow(UpcomingFilter())
    private val refreshing = MutableStateFlow(false)
    private val liveKp = MutableStateFlow<KpEstimate?>(null)

    init {
        viewModelScope.launch {
            refreshLiveKp()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val uiState: StateFlow<UpcomingUiState> = combine(
        combine(
            container.occurrenceRepo.observeAll(),
            container.locationRepo.observeAll(),
            container.ruleRepo.observeAll(),
            filter,
            refreshing,
        ) { occurrences, locations, rules, currentFilter, isRefreshing ->
            UpcomingBaseState(occurrences, locations, rules, currentFilter, isRefreshing)
        },
        liveKp,
    ) { base, currentKp ->
        base to currentKp?.estimatedKp
    }.flatMapLatest { (base, currentKp) ->
        // §7.3.2 rewrites the grid only as part of a source run, which reaches
        // us as an occurrence emission anyway — so it is read once per input
        // change here rather than once per tick below.
        val ovationGrid = AuroraSource.loadOvationGrid(container.sourceStateRepo)
        // §11/§9.2 step 1: same read-through visibility_cache the replan path
        // uses, so ticking doesn't recompute every visibility model for every
        // location on every wake (issue #18). Loaded/wrapped once per input
        // change, alongside ovationGrid above, for the same reason -- a tick
        // can fire far more often than the DB actually changes.
        val cache = VisibilityResultCache(container.visibilityCacheRepo.getAll(), TimeZone.currentSystemDefault())
        val cachedModels = cache.wrap(container.visibilityModels)
        // Every emission above restarts the ticking, which is exactly what
        // `flatMapLatest` is for: fresh inputs mean fresh boundaries.
        upcomingStatesOverTime(base, currentKp, ovationGrid, cachedModels, clock)
            .onEach { if (cache.dirty.isNotEmpty()) container.visibilityCacheRepo.upsertAll(cache.dirty) }
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        UpcomingUiState(now = clock.now()),
    )

    fun setScope(scope: UpcomingScope) = filter.update { it.copy(scope = scope) }

    fun togglePhenomenon(phenomenon: Phenomenon) = filter.update {
        it.copy(phenomena = if (phenomenon in it.phenomena) it.phenomena - phenomenon else it.phenomena + phenomenon)
    }

    fun refresh() {
        viewModelScope.launch {
            refreshing.value = true
            try {
                container.sourceRunner.runDue(
                    clock.now(),
                    force = enabledPolledSourceIds(container.polledSources),
                )
                refreshLiveKp()
            } finally {
                refreshing.value = false
            }
        }
    }

    private suspend fun enabledPolledSourceIds(polledSources: List<EventSource>): Set<String> =
        buildSet {
            for (source in polledSources) {
                if (container.settingsRepo.isSourceEnabled(source.id)) add(source.id)
            }
        }

    private suspend fun refreshLiveKp() {
        liveKp.value = if (container.settingsRepo.isSourceEnabled(SWPC_SOURCE_ID)) {
            runCatchingCancellable { KpNowcast.fetchLatest() }.getOrNull()
        } else {
            null
        }
    }

    private companion object {
        const val SWPC_SOURCE_ID = "swpc"
    }
}

/**
 * §13.2: the Android home screen keeps any active aurora nowcast visible above
 * the list, and §10.5's wording keys the state to the primary location.
 */
internal fun activeAuroraBanner(
    occurrences: List<Occurrence>,
    locations: List<SavedLocation>,
    visibilityModels: Map<Phenomenon, VisibilityModel>,
    ctx: VisibilityContext,
    currentKp: Double?,
): AuroraBannerUiState? {
    val primaryLocation = locations.firstOrNull { it.isPrimary } ?: locations.firstOrNull()
        ?: return null
    val auroraModel = visibilityModels[Phenomenon.AURORA] ?: return null
    val occurrence = occurrences.asSequence()
        .filter { it.phenomenon == Phenomenon.AURORA }
        .mapNotNull { occ ->
            val payload = occ.payload as? AuroraPayload ?: return@mapNotNull null
            if (payload.forecastKind != AuroraForecastKind.NOWCAST) return@mapNotNull null
            if (occ.window.start > ctx.now) return@mapNotNull null
            val expiresAt = occ.expiresAt
            if ((expiresAt != null && expiresAt <= ctx.now) || (expiresAt == null && occ.window.end <= ctx.now)) {
                return@mapNotNull null
            }
            occ to payload
        }
        .maxByOrNull { (_, payload) -> payload.issuedAt }
        ?.first
        ?: return null

    val details = auroraModel.evaluate(occurrence, primaryLocation, ctx).localDetails as? LocalDetails.AuroraLocal

    return AuroraBannerUiState(
        occurrenceId = occurrence.id,
        locationName = primaryLocation.name,
        ovationProbabilityPercent = details?.ovationProbability,
        currentKp = currentKp,
        issuedAt = (occurrence.payload as AuroraPayload).issuedAt,
        darknessStart = details?.darknessStart,
    )
}
