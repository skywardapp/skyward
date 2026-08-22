package dev.fritze.skyward.ui.upcoming

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.fritze.skyward.core.format.auroraLookDirection
import dev.fritze.skyward.core.format.refreshFailureMessage
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
import kotlinx.coroutines.flow.asStateFlow
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
     * Whether any location is saved at all. The empty list means two very
     * different things — "nothing is coming up" and "Skyward has nowhere to
     * compute visibility for" — and only the second one is the user's to fix
     * (#71). Onboarding lets location be skipped, so it is a reachable state,
     * not a theoretical one.
     */
    val hasLocations: Boolean = false,
    /**
     * The last live-Kp fetch failed, as opposed to having returned nothing.
     * The nowcast banner says "Live Kp unavailable" either way; this is what
     * lets it say *why*.
     */
    val liveKpFailed: Boolean = false,
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
    // issue #56: which horizon to watch, by hemisphere of the primary location.
    val lookDirection: String,
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
    private val liveKpFailed = MutableStateFlow(false)

    /**
     * §13.2: a transient line for the surface that has no other way to speak —
     * pull-to-refresh. `runDue` deliberately swallows a source's failure into
     * its diagnostics and carries on (§6.2), so without this the spinner just
     * stops and the screen looks unchanged whether the refresh reached NOAA or
     * never left the device. Cleared by the screen once shown.
     */
    private val _refreshMessage = MutableStateFlow<String?>(null)
    val refreshMessage: StateFlow<String?> = _refreshMessage.asStateFlow()

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
        liveKpFailed,
    ) { base, currentKp, kpFailed ->
        Triple(base, currentKp?.estimatedKp, kpFailed)
    }.flatMapLatest { (base, currentKp, kpFailed) ->
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
        upcomingStatesOverTime(base, currentKp, ovationGrid, cachedModels, clock, kpFailed)
            .onEach {
                // Snapshot before persisting and clear only what actually made
                // it to the DB: `dirty` only grows otherwise, and this flow
                // can tick many times per input change, which would re-upsert
                // the same rows on every subsequent tick.
                val toPersist = cache.dirty
                if (toPersist.isNotEmpty()) {
                    container.visibilityCacheRepo.upsertAll(toPersist)
                    cache.markPersisted(toPersist.keys)
                }
            }
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
                val forced = enabledPolledSourceIds(container.polledSources)
                container.sourceRunner.runDue(clock.now(), force = forced)
                refreshLiveKp()
                _refreshMessage.value = refreshFailureMessage(failedSourceIds(forced))
            } finally {
                refreshing.value = false
            }
        }
    }

    /** Dismissal is the screen's business: the message is shown once, then gone. */
    fun clearRefreshMessage() {
        _refreshMessage.value = null
    }

    /**
     * Only the sources this refresh actually ran are asked. Diagnostics
     * persist across runs, so a source that was not forced would answer with
     * its *last* run's verdict — reporting a failure the user has already
     * seen, or worse, one they already fixed.
     */
    private suspend fun failedSourceIds(forced: Set<String>): List<String> = buildList {
        for (source in container.polledSources) {
            if (source.id in forced && container.sourceRunner.getDiagnostics(source.id)?.ok == false) {
                add(source.id)
            }
        }
        // The banner's live Kp is a second, separate SWPC call (§7.3's nowcast
        // endpoint), so it can fail while the source run succeeded.
        if (liveKpFailed.value && SWPC_SOURCE_ID !in this) add(SWPC_SOURCE_ID)
    }

    private suspend fun enabledPolledSourceIds(polledSources: List<EventSource>): Set<String> =
        buildSet {
            for (source in polledSources) {
                if (container.settingsRepo.isSourceEnabled(source.id)) add(source.id)
            }
        }

    private suspend fun refreshLiveKp() {
        if (!container.settingsRepo.isSourceEnabled(SWPC_SOURCE_ID)) {
            liveKp.value = null
            // A source the user turned off is not a failure to report.
            liveKpFailed.value = false
            return
        }
        val result = runCatchingCancellable { KpNowcast.fetchLatest() }
        liveKpFailed.value = result.isFailure
        // A failed fetch keeps the last good estimate rather than blanking the
        // badge: a Kp from twenty minutes ago is still the best thing known.
        result.getOrNull()?.let { liveKp.value = it }
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
        lookDirection = auroraLookDirection(details?.geomagneticLatDeg ?: 0.0),
    )
}
