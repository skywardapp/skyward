package dev.fritze.skyward.ui.eventdetail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.fritze.skyward.core.model.Occurrence
import dev.fritze.skyward.core.model.Phenomenon
import dev.fritze.skyward.core.model.SavedLocation
import dev.fritze.skyward.core.model.VisibilityResult
import dev.fritze.skyward.core.rules.Anchor
import dev.fritze.skyward.core.rules.Cond
import dev.fritze.skyward.core.rules.NotifySchedule
import dev.fritze.skyward.core.rules.Rule
import dev.fritze.skyward.core.visibility.VisibilityContext
import dev.fritze.skyward.core.visibility.VisibilityModel
import dev.fritze.skyward.data.AppContainer
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.Duration

data class EventDetailUiState(
    val occurrence: Occurrence? = null,
    val perLocation: List<Pair<SavedLocation, VisibilityResult>> = emptyList(),
    val isMuted: Boolean = false,
    val extraReminderLead: Duration? = null,
    /**
     * True only until the repositories have been read once. A null
     * [occurrence] means two different things either side of that, and the
     * screen owes the user different copy for each: before, the row may still
     * be on its way; after, the occurrence has left the horizon window (an
     * expired aurora nowcast, a disabled source) and never will arrive.
     * Collapsing the two is what left a detail route stuck on "Loading…"
     * forever (issue #53).
     */
    val isLoading: Boolean = true,
    /** Distinguishes "no locations saved yet" from "visibility not evaluated yet" for the same reason. */
    val hasSavedLocations: Boolean = false,
)

/** §13.3: per-(occurrence, location) visibility for the detail table, plus the mute toggle. */
class EventDetailViewModel(private val container: AppContainer, private val occurrenceId: String) : ViewModel() {

    val uiState: StateFlow<EventDetailUiState> = combine(
        container.occurrenceRepo.observeAll(),
        container.locationRepo.observeAll(),
        container.ruleRepo.observeAll(),
    ) { occurrences, locations, rules ->
        eventDetailUiState(
            occurrenceId = occurrenceId,
            occurrences = occurrences,
            locations = locations,
            rules = rules,
            visibilityModels = container.visibilityModels,
            ctx = VisibilityContext(Clock.System.now(), null),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EventDetailUiState())

    /** §13.3: "mute this event" -- hidden(condition=OccurrenceIdIs(id), leads=[], notifyOnFirstSeen=false). */
    fun toggleMute() {
        viewModelScope.launch {
            val current = uiState.value
            if (current.isMuted) {
                container.ruleRepo.delete(muteRuleId(occurrenceId))
            } else {
                val occurrence = current.occurrence ?: return@launch
                val now = Clock.System.now()
                container.ruleRepo.upsert(
                    Rule(
                        id = muteRuleId(occurrenceId), name = "Muted: ${occurrence.title}", enabled = true,
                        phenomena = setOf(occurrence.phenomenon), locationIds = null,
                        condition = Cond.OccurrenceIdIs(occurrenceId),
                        schedule = NotifySchedule(emptyList(), Anchor.PEAK, notifyOnFirstSeen = false, quietHours = null),
                        hidden = true, createdAt = now, modifiedAt = now,
                    ),
                )
            }
            container.replanAndSync()
        }
    }

    /** §13.3: "add one-off extra reminder" -- hidden(condition=OccurrenceIdIs(id), leads=[lead]). */
    fun setExtraReminder(lead: Duration) {
        viewModelScope.launch {
            val occurrence = uiState.value.occurrence ?: return@launch
            val now = Clock.System.now()
            container.ruleRepo.upsert(
                Rule(
                    id = extraReminderRuleId(occurrenceId), name = "Extra reminder: ${occurrence.title}", enabled = true,
                    phenomena = setOf(occurrence.phenomenon), locationIds = null,
                    condition = Cond.OccurrenceIdIs(occurrenceId),
                    schedule = NotifySchedule(listOf(lead), Anchor.PEAK, notifyOnFirstSeen = false, quietHours = null),
                    hidden = true, createdAt = now, modifiedAt = now,
                ),
            )
            container.replanAndSync()
        }
    }

    fun removeExtraReminder() {
        viewModelScope.launch {
            container.ruleRepo.delete(extraReminderRuleId(occurrenceId))
            container.replanAndSync()
        }
    }
}

/**
 * The whole of §13.3's screen state as a pure function of what the database
 * holds, so the states the screen renders differently — loading, withdrawn,
 * present-but-locationless — are testable without a container or a clock.
 */
internal fun eventDetailUiState(
    occurrenceId: String,
    occurrences: List<Occurrence>,
    locations: List<SavedLocation>,
    rules: List<Rule>,
    visibilityModels: Map<Phenomenon, VisibilityModel>,
    ctx: VisibilityContext,
): EventDetailUiState {
    val occurrence = occurrences.firstOrNull { it.id == occurrenceId }
    val model = occurrence?.let { visibilityModels[it.phenomenon] }
    return EventDetailUiState(
        occurrence = occurrence,
        perLocation = if (occurrence != null && model != null) {
            locations.map { it to model.evaluate(occurrence, it, ctx) }
        } else {
            emptyList()
        },
        isMuted = rules.any { it.id == muteRuleId(occurrenceId) && it.enabled },
        extraReminderLead = rules.firstOrNull { it.id == extraReminderRuleId(occurrenceId) && it.enabled }
            ?.schedule?.leads?.firstOrNull(),
        isLoading = false,
        hasSavedLocations = locations.isNotEmpty(),
    )
}

internal fun muteRuleId(occurrenceId: String) = "mute:$occurrenceId"

internal fun extraReminderRuleId(occurrenceId: String) = "extra:$occurrenceId"
