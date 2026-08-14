package dev.fritze.skyward.ui.rules

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.fritze.skyward.core.rules.Rule
import dev.fritze.skyward.data.AppContainer
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.time.Clock

/** §13.1: the Rules list -- editing (name/phenomena/locations/condition/schedule) happens in [RuleEditorScreen] (§13.4, M5). */
class RulesViewModel(private val container: AppContainer) : ViewModel() {

    val rules: StateFlow<List<Rule>> = container.ruleRepo.observeVisible()
        .map { it.sortedBy { rule -> rule.name } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setEnabled(rule: Rule, enabled: Boolean) {
        viewModelScope.launch {
            container.ruleRepo.setEnabled(rule.id, enabled, Clock.System.now())
            container.replanAndSync()
        }
    }
}
