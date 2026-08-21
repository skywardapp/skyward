package dev.fritze.skyward.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.fritze.skyward.core.format.formatDateTime
import dev.fritze.skyward.core.sources.SourceDiagnostics
import dev.fritze.skyward.data.AppContainer
import kotlinx.datetime.TimeZone

/** §18/M4: per-source enable toggle + diagnostics (last success, last error). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourcesScreen(container: AppContainer, onBack: () -> Unit) {
    val viewModel: SourcesViewModel = viewModel { SourcesViewModel(container) }
    val rows by viewModel.rows.collectAsState()
    // §5: instants are stored and reasoned about in UTC and converted exactly
    // once, here at the UI edge. Read per composition rather than remembered —
    // a device that changes timezone should redraw in the new one.
    val zone = TimeZone.currentSystemDefault()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sources") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(rows, key = { it.id }) { row ->
                SourceRowCard(
                    row = row,
                    zone = zone,
                    onToggle = { enabled -> viewModel.setEnabled(row.id, enabled) },
                    onRefreshNow = { viewModel.refreshNow(row.id) },
                )
            }
        }
    }
}

@Composable
private fun SourceRowCard(row: SourceRow, zone: TimeZone, onToggle: (Boolean) -> Unit, onRefreshNow: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(row.displayName, style = MaterialTheme.typography.titleMedium)
                Switch(checked = row.enabled, onCheckedChange = onToggle)
            }
            Text(diagnosticsSummary(row.diagnostics, zone), style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic)
            if (row.polled && row.enabled) {
                TextButton(onClick = onRefreshNow) { Text("Refresh now") }
            }
        }
    }
}

/**
 * `Instant.toString()` renders "2026-08-21T14:03:22.512Z" — a machine's
 * timestamp, in a timezone the reader is probably not in, to a precision
 * nothing here needs (#71). Desktop has formatted these in local time since
 * M6; `core/format`'s formatter is now the one both frontends use.
 */
private fun diagnosticsSummary(diagnostics: SourceDiagnostics?, zone: TimeZone): String {
    if (diagnostics == null) return "Not yet run"
    val status = if (diagnostics.ok) "OK" else "Error: ${diagnostics.message ?: "unknown"}"
    val lastSuccess = diagnostics.lastSuccessAt?.let { "last success ${formatDateTime(it, zone)}" } ?: "never succeeded"
    return "$status • ${diagnostics.itemCount} item(s) • $lastSuccess"
}
