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
import dev.fritze.skyward.core.sources.SourceDiagnostics
import dev.fritze.skyward.data.AppContainer

/** §18/M4: per-source enable toggle + diagnostics (last success, last error). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourcesScreen(container: AppContainer, onBack: () -> Unit) {
    val viewModel: SourcesViewModel = viewModel { SourcesViewModel(container) }
    val rows by viewModel.rows.collectAsState()

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
                    onToggle = { enabled -> viewModel.setEnabled(row.id, enabled) },
                    onRefreshNow = { viewModel.refreshNow(row.id) },
                )
            }
        }
    }
}

@Composable
private fun SourceRowCard(row: SourceRow, onToggle: (Boolean) -> Unit, onRefreshNow: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(row.displayName, style = MaterialTheme.typography.titleMedium)
                Switch(checked = row.enabled, onCheckedChange = onToggle)
            }
            Text(diagnosticsSummary(row.diagnostics), style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic)
            if (row.polled && row.enabled) {
                TextButton(onClick = onRefreshNow) { Text("Refresh now") }
            }
        }
    }
}

private fun diagnosticsSummary(diagnostics: SourceDiagnostics?): String {
    if (diagnostics == null) return "Not yet run"
    val status = if (diagnostics.ok) "OK" else "Error: ${diagnostics.message ?: "unknown"}"
    val lastSuccess = diagnostics.lastSuccessAt?.let { "last success $it" } ?: "never succeeded"
    return "$status • ${diagnostics.itemCount} item(s) • $lastSuccess"
}
