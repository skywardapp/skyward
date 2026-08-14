package dev.fritze.skyward.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.fritze.skyward.core.model.GeoPoint
import dev.fritze.skyward.core.model.SavedLocation
import dev.fritze.skyward.core.rules.Rule
import dev.fritze.skyward.data.AppContainer
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.time.Clock

class OnboardingViewModel(private val container: AppContainer) : ViewModel() {

    val defaultRulePreview: StateFlow<List<Rule>> = container.ruleRepo.observeVisible()
        .map { it.sortedBy { rule -> rule.name } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // Launched on container.applicationScope (not viewModelScope): OnboardingScreen's onDone()
    // pops Routes.ONBOARDING as soon as finish() is called, which clears this ViewModel's
    // ViewModelStore and would cancel a viewModelScope coroutine mid-write.
    private var locationJob: Job? = null

    fun addFirstLocation(name: String, point: GeoPoint) {
        locationJob = container.applicationScope.launch {
            val now = Clock.System.now()
            container.locationRepo.upsert(SavedLocation(id = java.util.UUID.randomUUID().toString(), name = name, point = point, isPrimary = true, createdAt = now, modifiedAt = now))
        }
    }

    /** Awaits the location write (if any), seeds settings/sources, and unconditionally replans so the just-added location is reflected in scheduled notifications -- not left to runDue()'s own materiality-triggered replan, which only fires on a fresh install because every fetched occurrence happens to be new. */
    suspend fun finish() {
        locationJob?.join()
        container.settingsRepo.setOnboardingDone(true)
        container.sourceRunner.runDue(Clock.System.now(), force = container.computedSources.map { it.id }.toSet())
        container.replanAndSync()
    }
}
