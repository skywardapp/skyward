package dev.fritze.skyward.ui.rules

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
    val state = remember { RuleDraftState() }
    val stableNewId = remember { newRuleId() } // fixed for this screen instance, not regenerated on every recomposition
    var showDeleteConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(ruleId) { state.load(viewModel.load()) }

    val violations = ruleDraftViolations(state, existingRuleCount)
    val canSave = state.isSaveable(violations)

    Scaffold(
        topBar = {
            RuleEditorTopBar(
                isEditing = ruleId != null,
                canDelete = state.existing != null,
                onBack = onDone,
                onDeleteRequested = { showDeleteConfirm = true },
            )
        },
    ) { padding ->
        if (state.loaded) {
            RuleEditorForm(
                padding = padding,
                viewModel = viewModel,
                state = state,
                locations = locations,
                violations = violations,
                canSave = canSave,
                onSave = { viewModel.save(state.toRule(stableNewId), onDone) },
            )
        }
    }

    if (showDeleteConfirm) {
        DeleteRuleDialog(
            ruleName = state.existing?.name.orEmpty(),
            onDismiss = { showDeleteConfirm = false },
            onConfirm = { showDeleteConfirm = false; state.existing?.let { viewModel.delete(it, onDone) } },
        )
    }
}

/** All the editable fields of a [Rule] in progress, as a Compose state holder so [RuleEditorScreen] stays plain wiring. */
private class RuleDraftState {
    var existing by mutableStateOf<Rule?>(null); private set
    var loaded by mutableStateOf(false); private set

    var name by mutableStateOf("")
    var enabled by mutableStateOf(true)
    var phenomena by mutableStateOf(emptySet<Phenomenon>())
    var useAllLocations by mutableStateOf(true)
    var chosenLocationIds by mutableStateOf(emptySet<String>())
    var conditionRoot by mutableStateOf(ConditionNode.Group(GroupOp.AND, emptyList()))
    var schedule by mutableStateOf(ScheduleDraft(emptySet(), Anchor.PEAK, false, false, 22, 7))

    val locationIds: List<String>? get() = if (useAllLocations) null else chosenLocationIds.toList()

    fun load(rule: Rule?) {
        existing = rule
        if (rule != null) applyExisting(rule)
        loaded = true
    }

    private fun applyExisting(rule: Rule) {
        name = rule.name
        enabled = rule.enabled
        phenomena = rule.phenomena
        useAllLocations = rule.locationIds == null
        chosenLocationIds = rule.locationIds?.toSet() ?: emptySet()
        conditionRoot = rule.condition.toNode().let { it as? ConditionNode.Group ?: ConditionNode.Group(GroupOp.AND, listOf(it)) }
        schedule = ScheduleDraft(
            leads = rule.schedule.leads.toSet(),
            anchor = rule.schedule.anchor,
            notifyOnFirstSeen = rule.schedule.notifyOnFirstSeen,
            quietHoursEnabled = rule.schedule.quietHours != null,
            quietFromHour = rule.schedule.quietHours?.fromHour ?: 22,
            quietToHour = rule.schedule.quietHours?.toHour ?: 7,
        )
    }

    fun toRule(stableNewId: String): Rule {
        val now = Clock.System.now()
        val base = existing
        return Rule(
            id = base?.id ?: stableNewId,
            name = name,
            enabled = enabled,
            phenomena = phenomena,
            locationIds = locationIds,
            condition = conditionRoot.toCond(),
            schedule = NotifySchedule(
                leads = schedule.leads.sorted().reversed(),
                anchor = schedule.anchor,
                notifyOnFirstSeen = schedule.notifyOnFirstSeen,
                quietHours = if (schedule.quietHoursEnabled) QuietHours(schedule.quietFromHour, schedule.quietToHour) else null,
            ),
            hidden = base?.hidden ?: false,
            createdAt = base?.createdAt ?: now,
            modifiedAt = now,
        )
    }

    fun isSaveable(violations: List<String>): Boolean =
        loaded && name.isNotBlank() && phenomena.isNotEmpty() && conditionRoot.children.isNotEmpty() && violations.isEmpty()
}

/** §9.5's caps, checked against the in-progress draft, plus the rule-set-wide count (only relevant for a brand-new rule). */
private fun ruleDraftViolations(state: RuleDraftState, existingRuleCount: Int): List<String> {
    if (!state.loaded) return emptyList()
    val ruleSetCapExceeded = state.existing == null && existingRuleCount >= RuleLimits.MAX_RULES
    val capMessage = if (ruleSetCapExceeded) "you already have ${RuleLimits.MAX_RULES} rules, the maximum" else null
    return RuleLimits.violations(state.toRule("preview")) + listOfNotNull(capMessage)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RuleEditorTopBar(isEditing: Boolean, canDelete: Boolean, onBack: () -> Unit, onDeleteRequested: () -> Unit) {
    TopAppBar(
        title = { Text(if (isEditing) "Edit rule" else "Add rule") },
        navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
        actions = { if (canDelete) IconButton(onClick = onDeleteRequested) { Icon(Icons.Filled.Delete, contentDescription = "Delete rule") } },
    )
}

@Composable
private fun RuleEditorForm(
    padding: PaddingValues,
    viewModel: RuleEditorViewModel,
    state: RuleDraftState,
    locations: List<SavedLocation>,
    violations: List<String>,
    canSave: Boolean,
    onSave: () -> Unit,
) {
    LazyColumn(
        Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { OutlinedTextField(value = state.name, onValueChange = { state.name = it }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth()) }
        item { EnabledRow(state.enabled) { state.enabled = it } }
        item { PhenomenaSection(state.phenomena) { p -> state.phenomena = if (p in state.phenomena) state.phenomena - p else state.phenomena + p } }
        item {
            LocationsSection(
                locations = locations,
                useAllLocations = state.useAllLocations,
                chosenLocationIds = state.chosenLocationIds,
                onUseAllChange = { state.useAllLocations = it },
                onToggleLocation = { id -> state.chosenLocationIds = if (id in state.chosenLocationIds) state.chosenLocationIds - id else state.chosenLocationIds + id },
            )
        }
        item { ConditionsSection(state.phenomena, state.conditionRoot) { state.conditionRoot = it } }
        item { ScheduleSection(state.schedule) { state.schedule = it } }
        item { ViolationsCard(violations) }
        item { LivePreviewPanel(viewModel = viewModel, phenomena = state.phenomena, locationIds = state.locationIds, conditionRoot = state.conditionRoot) }
        item { Button(onClick = onSave, enabled = canSave, modifier = Modifier.fillMaxWidth()) { Text("Save") } }
    }
}

@Composable
private fun EnabledRow(enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text("Enabled", style = MaterialTheme.typography.bodyMedium)
        Switch(checked = enabled, onCheckedChange = onChange)
    }
}

@Composable
private fun PhenomenaSection(selected: Set<Phenomenon>, onToggle: (Phenomenon) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Phenomena", style = MaterialTheme.typography.titleSmall)
        PhenomenaChips(selected, onToggle)
    }
}

@Composable
private fun ConditionsSection(phenomena: Set<Phenomenon>, conditionRoot: ConditionNode.Group, onChange: (ConditionNode.Group) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Conditions", style = MaterialTheme.typography.titleSmall)
        if (phenomena.isEmpty()) {
            Text("Pick at least one phenomenon above to add conditions.", style = MaterialTheme.typography.bodySmall)
        } else {
            ConditionGroupEditor(node = conditionRoot, phenomena = phenomena, depth = 0, onChange = onChange, onDeleteSelf = null)
        }
    }
}

@Composable
private fun ScheduleSection(schedule: ScheduleDraft, onChange: (ScheduleDraft) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Schedule", style = MaterialTheme.typography.titleSmall)
        ScheduleEditor(schedule, onChange)
    }
}

@Composable
private fun ViolationsCard(violations: List<String>) {
    if (violations.isEmpty()) return
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            for (v in violations) Text(v, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun DeleteRuleDialog(ruleName: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete this rule?") },
        text = { Text("\"$ruleName\" will stop matching events and its reminders will be cancelled.") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Delete") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
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
private fun LocationsSection(
    locations: List<SavedLocation>,
    useAllLocations: Boolean,
    chosenLocationIds: Set<String>,
    onUseAllChange: (Boolean) -> Unit,
    onToggleLocation: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Locations", style = MaterialTheme.typography.titleSmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = useAllLocations, onCheckedChange = onUseAllChange)
            Text("All saved locations", modifier = Modifier.padding(start = 8.dp))
        }
        if (!useAllLocations) LocationChips(locations, chosenLocationIds, onToggleLocation)
    }
}

@Composable
private fun LocationChips(locations: List<SavedLocation>, chosenLocationIds: Set<String>, onToggleLocation: (String) -> Unit) {
    if (locations.isEmpty()) {
        Text("No saved locations yet.", style = MaterialTheme.typography.bodySmall)
        return
    }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (location in locations) {
            FilterChip(selected = location.id in chosenLocationIds, onClick = { onToggleLocation(location.id) }, label = { Text(location.name) })
        }
    }
}

private fun newRuleId(): String = java.util.UUID.randomUUID().toString()
