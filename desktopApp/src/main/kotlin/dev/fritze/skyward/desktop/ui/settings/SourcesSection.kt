package dev.fritze.skyward.desktop.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.fritze.skyward.core.format.sourceDisplayName
import dev.fritze.skyward.core.sources.SourceDiagnostics
import dev.fritze.skyward.core.sources.SourceKind
import dev.fritze.skyward.desktop.ui.DesktopAppState
import dev.fritze.skyward.desktop.ui.common.SectionCard
import dev.fritze.skyward.desktop.ui.common.formatDateTime

private data class SourceRow(
    val id: String,
    val displayName: String,
    val polled: Boolean,
    val enabled: Boolean,
    val diagnostics: SourceDiagnostics?,
)

/**
 * §6.2's per-source diagnostics, surfaced exactly as §7.3/§19 R3 require:
 * a parse failure has to be visible in the UI, not just swallowed into a
 * backoff counter.
 */
@Composable
internal fun SourcesSection(state: DesktopAppState) {
    val settings by state.settings.collectAsState()
    val refreshing by state.refreshingSources.collectAsState()
    var rows by remember { mutableStateOf<List<SourceRow>>(emptyList()) }

    // Diagnostics live in `source_state` as blobs, so unlike locations/rules
    // they can't be observed as a Flow — re-read them whenever a refresh
    // finishes or a source is toggled.
    LaunchedEffect(settings, refreshing) {
        rows = state.container.allSources.map { source ->
            SourceRow(
                id = source.id,
                displayName = sourceDisplayName(source.id),
                polled = source.kind == SourceKind.POLLED,
                enabled = state.container.settingsRepo.isSourceEnabled(source.id),
                diagnostics = state.container.sourceRunner.getDiagnostics(source.id),
            )
        }
    }

    SectionCard("Sources") {
        for (row in rows) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(row.displayName, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        diagnosticsLine(row, state),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (row.diagnostics?.ok == false) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(
                    onClick = { state.refreshSources(setOf(row.id)) },
                    enabled = row.enabled && row.id !in refreshing,
                ) { Text("Refresh") }
                Switch(
                    checked = row.enabled,
                    onCheckedChange = { enabled -> state.launch { setSourceEnabled(state, row.id, enabled) } },
                )
            }
        }
    }
}

private suspend fun setSourceEnabled(state: DesktopAppState, sourceId: String, enabled: Boolean) {
    state.container.settingsRepo.setSourceEnabled(sourceId, enabled)
    if (!enabled) {
        // Same reasoning as Android's SourcesViewModel: a disabled source never
        // runs again, so its own withdraw-stale-occurrence pass (§6.3) never
        // gets a chance to prune its rows, and already-planned notifications
        // for them would keep firing forever.
        for (occurrenceId in state.container.occurrenceRepo.getIdsBySource(sourceId)) {
            state.container.occurrenceRepo.deleteById(occurrenceId)
        }
        state.container.replan()
    }
}

private fun diagnosticsLine(row: SourceRow, state: DesktopAppState): String {
    val diagnostics = row.diagnostics ?: return if (row.polled) "Polled · never run yet" else "Computed · never run yet"
    val kind = if (row.polled) "Polled" else "Computed"
    val lastSuccess = diagnostics.lastSuccessAt?.let { "last success ${formatDateTime(it, state.zone)}" } ?: "no successful run yet"
    // A blank message adds a trailing " · " and nothing else; a failed run with
    // no message still needs *something* said about it.
    val problem = diagnostics.message?.takeIf { it.isNotBlank() }
        ?: "the last run failed".takeIf { !diagnostics.ok }
    return listOfNotNull("$kind · ${diagnostics.itemCount} events · $lastSuccess", problem).joinToString(" · ")
}

