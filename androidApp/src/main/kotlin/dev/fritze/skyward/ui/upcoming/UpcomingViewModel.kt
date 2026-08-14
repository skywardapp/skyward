package dev.fritze.skyward.ui.upcoming

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.fritze.skyward.core.model.Phenomenon
import dev.fritze.skyward.core.planner.UpcomingFilter
import dev.fritze.skyward.core.planner.UpcomingItem
import dev.fritze.skyward.core.planner.UpcomingScope
import dev.fritze.skyward.core.planner.computeUpcomingItems
import dev.fritze.skyward.core.visibility.VisibilityContext
import dev.fritze.skyward.data.AppContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Clock

data class UpcomingUiState(
    val items: List<UpcomingItem> = emptyList(),
    val filter: UpcomingFilter = UpcomingFilter(),
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
)

/** §13.2's core view-model — combines DB state reactively, delegates the actual selection logic to `core`'s pure `computeUpcomingItems`. */
class UpcomingViewModel(private val container: AppContainer) : ViewModel() {
    private val filter = MutableStateFlow(UpcomingFilter())
    private val refreshing = MutableStateFlow(false)

    val uiState: StateFlow<UpcomingUiState> = combine(
        container.occurrenceRepo.observeAll(),
        container.locationRepo.observeAll(),
        container.ruleRepo.observeAll(),
        filter,
        refreshing,
    ) { occurrences, locations, rules, currentFilter, isRefreshing ->
        val ctx = VisibilityContext(now = Clock.System.now(), ovationGrid = null)
        val items = computeUpcomingItems(occurrences, locations, rules, container.visibilityModels, ctx, currentFilter)
        UpcomingUiState(items, currentFilter, isLoading = false, isRefreshing = isRefreshing)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UpcomingUiState())

    fun setScope(scope: UpcomingScope) = filter.update { it.copy(scope = scope) }

    fun togglePhenomenon(phenomenon: Phenomenon) = filter.update {
        it.copy(phenomena = if (phenomenon in it.phenomena) it.phenomena - phenomenon else it.phenomena + phenomenon)
    }

    fun refresh() {
        viewModelScope.launch {
            refreshing.value = true
            try {
                container.sourceRunner.runDue(Clock.System.now(), force = container.computedSources.map { it.id }.toSet())
            } finally {
                refreshing.value = false
            }
        }
    }
}
