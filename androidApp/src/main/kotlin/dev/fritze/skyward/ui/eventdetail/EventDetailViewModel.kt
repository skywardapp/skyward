package dev.fritze.skyward.ui.eventdetail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.fritze.skyward.core.model.Occurrence
import dev.fritze.skyward.core.model.SavedLocation
import dev.fritze.skyward.core.model.VisibilityResult
import dev.fritze.skyward.core.rules.Anchor
import dev.fritze.skyward.core.rules.Cond
import dev.fritze.skyward.core.rules.NotifySchedule
import dev.fritze.skyward.core.rules.Rule
import dev.fritze.skyward.core.visibility.VisibilityContext
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
)

/** §13.3: per-(occurrence, location) visibility for the detail table, plus the mute toggle. */
class EventDetailViewModel(private val container: AppContainer, private val occurrenceId: String) : ViewModel() {

    val uiState: StateFlow<EventDetailUiState> = combine(
        container.occurrenceRepo.observeAll(),
        container.locationRepo.observeAll(),
        container.ruleRepo.observeAll(),
    ) { occurrences, locations, rules ->
        val occurrence = occurrences.firstOrNull { it.id == occurrenceId }
        val model = occurrence?.let { container.visibilityModels[it.phenomenon] }
        val perLocation = if (occurrence != null && model != null) {
            val ctx = VisibilityContext(Clock.System.now(), null)
            locations.map { it to model.evaluate(occurrence, it, ctx) }
        } else {
            emptyList()
        }
        EventDetailUiState(
            occurrence, perLocation,
            isMuted = rules.any { it.id == muteRuleId() && it.enabled },
            extraReminderLead = rules.firstOrNull { it.id == extraReminderRuleId() && it.enabled }?.schedule?.leads?.firstOrNull(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EventDetailUiState())

    /** §13.3: "mute this event" -- hidden(condition=OccurrenceIdIs(id), leads=[], notifyOnFirstSeen=false). */
    fun toggleMute() {
        viewModelScope.launch {
            val current = uiState.value
            if (current.isMuted) {
                container.ruleRepo.delete(muteRuleId())
            } else {
                val occurrence = current.occurrence ?: return@launch
                val now = Clock.System.now()
                container.ruleRepo.upsert(
                    Rule(
                        id = muteRuleId(), name = "Muted: ${occurrence.title}", enabled = true,
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

    private fun muteRuleId() = "mute:$occurrenceId"

    /** §13.3: "add one-off extra reminder" -- hidden(condition=OccurrenceIdIs(id), leads=[lead]). */
    fun setExtraReminder(lead: Duration) {
        viewModelScope.launch {
            val occurrence = uiState.value.occurrence ?: return@launch
            val now = Clock.System.now()
            container.ruleRepo.upsert(
                Rule(
                    id = extraReminderRuleId(), name = "Extra reminder: ${occurrence.title}", enabled = true,
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
            container.ruleRepo.delete(extraReminderRuleId())
            container.replanAndSync()
        }
    }

    private fun extraReminderRuleId() = "extra:$occurrenceId"
}
