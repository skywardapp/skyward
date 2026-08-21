package dev.fritze.skyward.desktop.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.fritze.skyward.core.model.Occurrence
import dev.fritze.skyward.core.model.SavedLocation
import dev.fritze.skyward.core.persistence.ThemeChoice
import dev.fritze.skyward.core.rules.Rule
import dev.fritze.skyward.core.visibility.OvationGrid
import dev.fritze.skyward.core.visibility.VisibilityContext
import dev.fritze.skyward.desktop.autostart.AutostartManager
import dev.fritze.skyward.desktop.data.DesktopContainer
import dev.fritze.skyward.desktop.data.DesktopPaths
import dev.fritze.skyward.desktop.notify.DesktopNotifier
import dev.fritze.skyward.desktop.notify.FallbackChainNotifier
import dev.fritze.skyward.desktop.scheduler.MissedReminder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** §14's left nav rail: [Overview] [Map] [Timeline] [Sky chart] [Aurora] [Rules] [Settings]. */
enum class Destination(val title: String, val glyph: String) {
    OVERVIEW("Overview", "◎"),
    MAP("Map", "▦"),
    TIMELINE("Timeline", "▤"),
    SKY_CHART("Sky chart", "✳"),
    AURORA("Aurora", "≈"),
    RULES("Rules", "⌘"),
    SETTINGS("Settings", "⚙"),
}

/**
 * The desktop app's shared reactive state — the thing §18's M6 line means by
 * "reusing core view-models with desktop layouts". Every screen reads the
 * same DB-backed flows from here and calls `:core`'s pure functions on them
 * (`computeUpcomingItems`, `Planner.computeMatches`, the visibility models);
 * no screen owns its own copy of the domain state.
 *
 * Not a `ViewModel`: androidx.lifecycle isn't a desktop dependency here, and
 * the whole window shares one instance for the process lifetime anyway.
 */
class DesktopAppState(
    val container: DesktopContainer,
    private val scope: CoroutineScope,
    /** §10.3's desktop-integration seams, injected so Settings can drive them and tests can fake them. */
    val autostart: AutostartManager = AutostartManager.forEnvironment(),
    val notifier: DesktopNotifier = FallbackChainNotifier.default(),
    val paths: DesktopPaths = DesktopPaths(),
    val zone: TimeZone = TimeZone.currentSystemDefault(),
    private val clock: Clock = Clock.System,
) {

    /**
     * Drives everything that depends on "now" — countdowns, tonight's
     * darkness windows, the timeline cursor, aurora margins. One minute is
     * fine-grained enough for all of them and coarse enough that the
     * visibility re-evaluation it triggers stays comfortably in the noise.
     */
    val tick: StateFlow<Instant> = flow {
        while (true) {
            emit(clock.now())
            delay(TICK_INTERVAL)
        }
    }.stateIn(scope, SharingStarted.Eagerly, clock.now())

    val occurrences: StateFlow<List<Occurrence>> =
        container.occurrenceRepo.observeAll().stateIn(scope, SharingStarted.Eagerly, emptyList())

    val locations: StateFlow<List<SavedLocation>> =
        container.locationRepo.observeAll().stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** Includes hidden rules — needed anywhere evaluation happens (§9.1). */
    val allRules: StateFlow<List<Rule>> =
        container.ruleRepo.observeAll().stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** The Rules screen's list: user-authored rules only (§13.3). */
    val visibleRules: StateFlow<List<Rule>> =
        container.ruleRepo.observeVisible().stateIn(scope, SharingStarted.Eagerly, emptyList())

    val settings: StateFlow<Map<String, String>> =
        container.settingsRepo.observeAll().stateIn(scope, SharingStarted.Eagerly, emptyMap())

    val theme: StateFlow<ThemeChoice> =
        settings.map { ThemeChoice.parse(it["theme"]) }.stateIn(scope, SharingStarted.Eagerly, ThemeChoice.SYSTEM)

    /**
     * The latest OVATION nowcast grid (§7.3.1). Lives in `source_state` as a
     * gzipped blob rather than a queryable table, so unlike everything above
     * it can't be a DB Flow — it is re-read after every refresh instead.
     */
    private val _ovationGrid = MutableStateFlow<OvationGrid?>(null)
    val ovationGrid: StateFlow<OvationGrid?> = _ovationGrid

    /** §10.3's startup panel; cleared once the user dismisses it. */
    private val _missedWhileAway = MutableStateFlow<List<MissedReminder>>(emptyList())
    val missedWhileAway: StateFlow<List<MissedReminder>> = _missedWhileAway

    /**
     * A reminder came due and no notification backend would show it (§10.3's
     * DBus → notify-send chain exhausted). The scheduler logs that to stderr,
     * which is not a place a user looks; this is what puts it in the window.
     */
    private val _notifierUnavailable = MutableStateFlow(false)
    val notifierUnavailable: StateFlow<Boolean> = _notifierUnavailable

    private val _refreshingSources = MutableStateFlow<Set<String>>(emptySet())
    val refreshingSources: StateFlow<Set<String>> = _refreshingSources

    var destination by mutableStateOf(Destination.OVERVIEW)
        private set

    /** The occurrence shown in the detail pane, if any — shared by Overview, Map and Timeline (§14). */
    var selectedOccurrenceId by mutableStateOf<String?>(null)
        private set

    fun navigateTo(target: Destination) {
        destination = target
    }

    fun selectOccurrence(occurrenceId: String?) {
        selectedOccurrenceId = occurrenceId
    }

    /** Opens the detail pane for [occurrenceId] from wherever the user clicked (a notification, the map, the tray). */
    fun openOccurrence(occurrenceId: String) {
        selectedOccurrenceId = occurrenceId
        if (destination == Destination.SETTINGS || destination == Destination.RULES) destination = Destination.OVERVIEW
    }

    fun setMissedWhileAway(missed: List<MissedReminder>) {
        _missedWhileAway.value = missed
    }

    fun dismissMissedWhileAway() {
        _missedWhileAway.value = emptyList()
    }

    /**
     * Both outcomes matter. A success retracts the warning — a notification
     * daemon that has come back (a desktop-session restart, a portal that
     * finally answered) should not leave a claim standing that it is broken —
     * and the Settings "send a test notification" button reports through here
     * for exactly that reason.
     */
    fun recordDeliveryOutcome(delivered: Boolean) {
        _notifierUnavailable.value = !delivered
    }

    /** Dismissal is per-failure: the next undeliverable reminder raises it again. */
    fun dismissNotifierUnavailable() {
        _notifierUnavailable.value = false
    }

    fun visibilityContext(now: Instant = tick.value): VisibilityContext = VisibilityContext(now, _ovationGrid.value)

    suspend fun reloadOvationGrid() {
        _ovationGrid.value = container.latestOvationGrid()
    }

    /**
     * Force-refreshes [sourceIds] and re-plans. Backs both the Overview
     * refresh button and §14.4's "dashboard-open forces active polling tier"
     * — the aurora screen simply forces `swpc`.
     */
    fun refreshSources(sourceIds: Set<String>) {
        if (sourceIds.isEmpty()) return
        scope.launch {
            // Claimed atomically, and only the ids this call actually claimed
            // are released: two overlapping refreshes (the Overview button
            // while the aurora screen is forcing `swpc`) would otherwise let
            // the first to finish clear the other's spinner.
            var claimed = emptySet<String>()
            _refreshingSources.update { inFlight ->
                claimed = sourceIds - inFlight
                inFlight + claimed
            }
            if (claimed.isEmpty()) return@launch
            try {
                container.sourceRunner.runDue(clock.now(), force = claimed)
                reloadOvationGrid()
            } finally {
                _refreshingSources.update { it - claimed }
            }
        }
    }

    fun refreshEverything() = refreshSources(container.allSources.mapTo(mutableSetOf()) { it.id })

    fun launch(block: suspend () -> Unit) {
        scope.launch { block() }
    }

    private companion object {
        val TICK_INTERVAL = 60.seconds
    }
}
