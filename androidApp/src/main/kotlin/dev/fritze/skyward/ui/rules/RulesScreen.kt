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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.fritze.skyward.core.format.phenomenonLabel
import dev.fritze.skyward.core.rules.Rule
import dev.fritze.skyward.data.AppContainer

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RulesScreen(container: AppContainer, onAdd: () -> Unit, onEdit: (String) -> Unit) {
    val viewModel: RulesViewModel = viewModel { RulesViewModel(container) }
    val rules by viewModel.rules.collectAsState()

    Scaffold(
        topBar = { TopAppBar(title = { Text("Rules") }) },
        floatingActionButton = {
            FloatingActionButton(onClick = onAdd) { Icon(Icons.Filled.Add, contentDescription = "Add rule") }
        },
    ) { padding ->
        if (rules.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(padding).padding(32.dp)) {
                Text("No rules yet. Default rules are created on first launch — tap + to add your own.")
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(rules, key = { it.id }) { rule ->
                    RuleRow(rule, onToggle = { viewModel.setEnabled(rule, it) }, onClick = { onEdit(rule.id) })
                }
            }
        }
    }
}

@Composable
private fun RuleRow(rule: Rule, onToggle: (Boolean) -> Unit, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(rule.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Switch(checked = rule.enabled, onCheckedChange = onToggle)
            }
            Text(rule.phenomena.joinToString(", ") { phenomenonLabel(it) }, style = MaterialTheme.typography.bodySmall)
            if (rule.schedule.leads.isNotEmpty()) {
                Text("Reminds: " + rule.schedule.leads.joinToString(", ") { formatLead(it) }, style = MaterialTheme.typography.bodyMedium)
            } else if (rule.schedule.notifyOnFirstSeen) {
                Text("Reminds: as soon as matched", style = MaterialTheme.typography.bodyMedium)
            } else {
                // The row said nothing at all for a silent rule, which read as
                // "reminders not shown here" rather than "there are none"
                // (#73). Reachable for rules saved before the editor warned,
                // and for anything §12.3's sync import brings in.
                Text(
                    "Sends no reminders",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
