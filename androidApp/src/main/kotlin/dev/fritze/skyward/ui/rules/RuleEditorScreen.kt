package dev.fritze.skyward.ui.rules

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.fritze.skyward.core.model.Phenomenon
import dev.fritze.skyward.core.model.SavedLocation
import dev.fritze.skyward.core.rules.Anchor
import dev.fritze.skyward.core.rules.NotifySchedule
import dev.fritze.skyward.core.rules.QuietHours
import dev.fritze.skyward.core.rules.Rule
import dev.fritze.skyward.core.rules.RuleLimits
import dev.fritze.skyward.data.AppContainer
import dev.fritze.skyward.ui.common.phenomenonLabel
import kotlin.time.Clock
import kotlin.time.Duration

/**
 * §13.4: "Rule = name + phenomena multi-select + locations select + condition
 * builder + schedule editor." Accept criteria for M5 (#6): build the §9.6
 * "worth a trip" rule from scratch via this screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RuleEditorScreen(container: AppContainer, ruleId: String?, onDone: () -> Unit) {
    val viewModel: RuleEditorViewModel = viewModel { RuleEditorViewModel(container, ruleId) }
    val locations by viewModel.locations.collectAsState()
    val existingRuleCount by viewModel.ruleCount.collectAsState()

    var loaded by remember { mutableStateOf(false) }
    var existing by remember { mutableStateOf<Rule?>(null) }

    var name by remember { mutableStateOf("") }
    var enabled by remember { mutableStateOf(true) }
    var phenomena by remember { mutableStateOf(emptySet<Phenomenon>()) }
    var useAllLocations by remember { mutableStateOf(true) }
    var chosenLocationIds by remember { mutableStateOf(emptySet<String>()) }
    var conditionRoot by remember { mutableStateOf<ConditionNode.Group>(ConditionNode.Group(GroupOp.AND, emptyList())) }
    var leads by remember { mutableStateOf(emptySet<Duration>()) }
    var anchor by remember { mutableStateOf(Anchor.PEAK) }
    var notifyOnFirstSeen by remember { mutableStateOf(false) }
    var quietHoursEnabled by remember { mutableStateOf(false) }
    var quietFromHour by remember { mutableStateOf(22) }
    var quietToHour by remember { mutableStateOf(7) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    val stableNewId = remember { newRuleId() } // fixed for this screen instance, not regenerated on every recomposition

    LaunchedEffect(ruleId) {
        val rule = viewModel.load()
        existing = rule
        if (rule != null) {
            name = rule.name
            enabled = rule.enabled
            phenomena = rule.phenomena
            useAllLocations = rule.locationIds == null
            chosenLocationIds = rule.locationIds?.toSet() ?: emptySet()
            conditionRoot = rule.condition.toNode().let { it as? ConditionNode.Group ?: ConditionNode.Group(GroupOp.AND, listOf(it)) }
            leads = rule.schedule.leads.toSet()
            anchor = rule.schedule.anchor
            notifyOnFirstSeen = rule.schedule.notifyOnFirstSeen
            quietHoursEnabled = rule.schedule.quietHours != null
            quietFromHour = rule.schedule.quietHours?.fromHour ?: 22
            quietToHour = rule.schedule.quietHours?.toHour ?: 7
        }
        loaded = true
    }

    fun buildDraft(): Rule {
        val now = Clock.System.now()
        return Rule(
            id = existing?.id ?: stableNewId,
            name = name,
            enabled = enabled,
            phenomena = phenomena,
            locationIds = if (useAllLocations) null else chosenLocationIds.toList(),
            condition = conditionRoot.toCond(),
            schedule = NotifySchedule(
                leads = leads.sorted().reversed(),
                anchor = anchor,
                notifyOnFirstSeen = notifyOnFirstSeen,
                quietHours = if (quietHoursEnabled) QuietHours(quietFromHour, quietToHour) else null,
            ),
            hidden = existing?.hidden ?: false,
            createdAt = existing?.createdAt ?: now,
            modifiedAt = now,
        )
    }

    // §9.5: the rule-set-wide cap only applies to a brand-new rule (editing an existing one doesn't change the count).
    val ruleSetCapExceeded = existing == null && existingRuleCount >= RuleLimits.MAX_RULES
    val violations = if (loaded) {
        RuleLimits.violations(buildDraft()) + listOfNotNull(
            if (ruleSetCapExceeded) "you already have ${RuleLimits.MAX_RULES} rules, the maximum" else null,
        )
    } else {
        emptyList()
    }
    val canSave = loaded && name.isNotBlank() && phenomena.isNotEmpty() && conditionRoot.children.isNotEmpty() && violations.isEmpty()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (ruleId == null) "Add rule" else "Edit rule") },
                navigationIcon = { IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                actions = {
                    if (existing != null) {
                        IconButton(onClick = { showDeleteConfirm = true }) { Icon(Icons.Filled.Delete, contentDescription = "Delete rule") }
                    }
                },
            )
        },
    ) { padding ->
        if (!loaded) return@Scaffold

        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Enabled", style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                }
            }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Phenomena", style = MaterialTheme.typography.titleSmall)
                    PhenomenaChips(selected = phenomena) { p -> phenomena = if (p in phenomena) phenomena - p else phenomena + p }
                }
            }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Locations", style = MaterialTheme.typography.titleSmall)
                    LocationsSelector(
                        locations = locations,
                        useAllLocations = useAllLocations,
                        chosenLocationIds = chosenLocationIds,
                        onUseAllChange = { useAllLocations = it },
                        onToggleLocation = { id -> chosenLocationIds = if (id in chosenLocationIds) chosenLocationIds - id else chosenLocationIds + id },
                    )
                }
            }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Conditions", style = MaterialTheme.typography.titleSmall)
                    if (phenomena.isEmpty()) {
                        Text("Pick at least one phenomenon above to add conditions.", style = MaterialTheme.typography.bodySmall)
                    } else {
                        ConditionGroupEditor(node = conditionRoot, phenomena = phenomena, depth = 0, onChange = { conditionRoot = it }, onDeleteSelf = null)
                    }
                }
            }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Schedule", style = MaterialTheme.typography.titleSmall)
                    ScheduleEditor(
                        leads = leads,
                        onLeadsChange = { leads = it },
                        anchor = anchor,
                        onAnchorChange = { anchor = it },
                        notifyOnFirstSeen = notifyOnFirstSeen,
                        onNotifyOnFirstSeenChange = { notifyOnFirstSeen = it },
                        quietHoursEnabled = quietHoursEnabled,
                        onQuietHoursEnabledChange = { quietHoursEnabled = it },
                        quietFromHour = quietFromHour,
                        quietToHour = quietToHour,
                        onQuietHoursChange = { from, to -> quietFromHour = from; quietToHour = to },
                    )
                }
            }

            item {
                if (violations.isNotEmpty()) {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            for (v in violations) Text(v, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            item {
                LivePreviewPanel(
                    viewModel = viewModel,
                    phenomena = phenomena,
                    locationIds = if (useAllLocations) null else chosenLocationIds.toList(),
                    conditionRoot = conditionRoot,
                )
            }

            item {
                Button(onClick = { viewModel.save(buildDraft(), onDone) }, enabled = canSave, modifier = Modifier.fillMaxWidth()) {
                    Text("Save")
                }
            }
        }
    }

    if (showDeleteConfirm) {
        val toDelete = existing
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete this rule?") },
            text = { Text("\"${toDelete?.name}\" will stop matching events and its reminders will be cancelled.") },
            confirmButton = {
                TextButton(onClick = { showDeleteConfirm = false; toDelete?.let { viewModel.delete(it, onDone) } }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun PhenomenaChips(selected: Set<Phenomenon>, onToggle: (Phenomenon) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (phenomenon in Phenomenon.entries) {
            FilterChip(selected = phenomenon in selected, onClick = { onToggle(phenomenon) }, label = { Text(phenomenonLabel(phenomenon)) })
        }
    }
}

@Composable
private fun LocationsSelector(
    locations: List<SavedLocation>,
    useAllLocations: Boolean,
    chosenLocationIds: Set<String>,
    onUseAllChange: (Boolean) -> Unit,
    onToggleLocation: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = useAllLocations, onCheckedChange = onUseAllChange)
            Text("All saved locations", modifier = Modifier.padding(start = 8.dp))
        }
        if (!useAllLocations) {
            if (locations.isEmpty()) {
                Text("No saved locations yet.", style = MaterialTheme.typography.bodySmall)
            } else {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    for (location in locations) {
                        FilterChip(selected = location.id in chosenLocationIds, onClick = { onToggleLocation(location.id) }, label = { Text(location.name) })
                    }
                }
            }
        }
    }
}

private fun newRuleId(): String = java.util.UUID.randomUUID().toString()
