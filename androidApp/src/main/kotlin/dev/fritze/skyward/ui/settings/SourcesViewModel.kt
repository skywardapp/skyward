package dev.fritze.skyward.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.fritze.skyward.core.sources.SourceDiagnostics
import dev.fritze.skyward.data.AppContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.time.Clock

data class SourceRow(
    val id: String,
    val displayName: String,
    val polled: Boolean,
    val enabled: Boolean,
    val diagnostics: SourceDiagnostics?,
)

/** §18/M4: backs the Sources settings screen -- per-source enable toggle plus [SourceDiagnostics] (last success, last error, item count), already modeled by `SourceRunner` (§6.2). */
class SourcesViewModel(private val container: AppContainer) : ViewModel() {
    private val _rows = MutableStateFlow<List<SourceRow>>(emptyList())
    val rows: StateFlow<List<SourceRow>> = _rows.asStateFlow()

    init {
        reload()
    }

    fun reload() {
        viewModelScope.launch {
            val allSources = container.computedSources + container.polledSources
            _rows.value = allSources.map { source ->
                SourceRow(
                    id = source.id,
                    displayName = displayName(source.id),
                    polled = source in container.polledSources,
                    enabled = container.settingsRepo.isSourceEnabled(source.id),
                    diagnostics = container.sourceRunner.getDiagnostics(source.id),
                )
            }
        }
    }

    fun setEnabled(sourceId: String, enabled: Boolean) {
        viewModelScope.launch {
            container.settingsRepo.setSourceEnabled(sourceId, enabled)
            reload()
        }
    }

    /** Pull-to-refresh for one source (§13.2's per-screen refresh, scoped here to a single row). */
    fun refreshNow(sourceId: String) {
        viewModelScope.launch {
            container.sourceRunner.runDue(Clock.System.now(), force = setOf(sourceId))
            reload()
        }
    }

    private fun displayName(id: String): String = when (id) {
        "swpc" -> "Aurora (NOAA SWPC)"
        "jpl" -> "Comets (JPL)"
        "eonet" -> "Terrestrial events (NASA EONET)"
        "eclipse" -> "Eclipses"
        "meteors" -> "Meteor showers"
        "moon" -> "Moon events"
        "conjunctions" -> "Conjunctions"
        else -> id
    }
}
