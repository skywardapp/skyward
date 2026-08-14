package dev.fritze.skyward.ui.rules

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.fritze.skyward.core.rules.Rule
import dev.fritze.skyward.data.AppContainer
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

private val LEAD_PRESETS = listOf(2.hours, 1.days, 3.days, 7.days, 30.days, 180.days)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RulesScreen(container: AppContainer) {
    val viewModel: RulesViewModel = viewModel { RulesViewModel(container) }
    val rules by viewModel.rules.collectAsState()
    var editingRule by remember { mutableStateOf<Rule?>(null) }

    Scaffold(topBar = { TopAppBar(title = { Text("Rules") }) }) { padding ->
        if (rules.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(padding).padding(32.dp)) {
                Text("No rules yet. Default rules are created on first launch.")
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(rules, key = { it.id }) { rule ->
                    RuleRow(rule, onToggle = { viewModel.setEnabled(rule, it) }, onEditLeads = { editingRule = rule })
                }
            }
        }
    }

    editingRule?.let { rule ->
        LeadEditorDialog(
            rule = rule,
            onDismiss = { editingRule = null },
            onSave = { leads -> viewModel.setLeads(rule, leads); editingRule = null },
        )
    }
}

@Composable
private fun RuleRow(rule: Rule, onToggle: (Boolean) -> Unit, onEditLeads: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(rule.name, style = MaterialTheme.typography.titleMedium)
                Switch(checked = rule.enabled, onCheckedChange = onToggle)
            }
            Text(
                rule.phenomena.joinToString(", ") { it.name.lowercase().replace('_', ' ') },
                style = MaterialTheme.typography.bodySmall,
            )
            if (rule.schedule.leads.isNotEmpty()) {
                Text("Reminds: " + rule.schedule.leads.joinToString(", ") { formatLead(it) }, style = MaterialTheme.typography.bodyMedium)
            }
            TextButton(onClick = onEditLeads) { Text("Edit reminder timing") }
        }
    }
}

@Composable
private fun LeadEditorDialog(rule: Rule, onDismiss: () -> Unit, onSave: (List<Duration>) -> Unit) {
    var selected by remember { mutableStateOf(rule.schedule.leads.toSet()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Reminder timing — ${rule.name}") },
        text = {
            Column {
                for (preset in LEAD_PRESETS) {
                    FilterChip(
                        selected = preset in selected,
                        onClick = { selected = if (preset in selected) selected - preset else selected + preset },
                        label = { Text(formatLead(preset)) },
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(selected.sorted().reversed()) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun formatLead(duration: Duration): String = when {
    duration.inWholeDays >= 30 -> "${duration.inWholeDays / 30} month${if (duration.inWholeDays / 30 == 1L) "" else "s"} before"
    duration.inWholeDays >= 1 -> "${duration.inWholeDays} day${if (duration.inWholeDays == 1L) "" else "s"} before"
    else -> "${duration.inWholeHours} hour${if (duration.inWholeHours == 1L) "" else "s"} before"
}
