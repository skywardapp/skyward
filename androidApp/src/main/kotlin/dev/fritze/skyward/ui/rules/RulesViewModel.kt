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
import kotlin.time.Duration

/**
 * §13.1/§18: M3 scope is a read-only rule list plus enable/disable and lead
 * editing only -- the full structured condition builder (§13.4) is M5.
 */
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

    fun setLeads(rule: Rule, leads: List<Duration>) {
        viewModelScope.launch {
            val now = Clock.System.now()
            container.ruleRepo.upsert(rule.copy(schedule = rule.schedule.copy(leads = leads), modifiedAt = now))
            container.replanAndSync()
        }
    }
}
