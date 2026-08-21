package dev.fritze.skyward.ui.rules

import androidx.activity.compose.BackHandler
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
import dev.fritze.skyward.core.format.deleteRuleConfirmation
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
import kotlin.time.Instant

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
    var showDiscardConfirm by remember { mutableStateOf(false) }
    val error by viewModel.error.collectAsState()

    LaunchedEffect(ruleId) { state.load(viewModel.load()) }

    val violations = ruleDraftViolations(state, existingRuleCount)
    val canSave = state.isSaveable(violations)

    // Leaving the editor threw every edit away without a word, from the back
    // arrow and from the system back gesture alike. Both now ask, and only
    // when there is actually something to lose.
    val leave: () -> Unit = {
        if (state.isDirty(stableNewId)) {
            showDiscardConfirm = true
        } else {
            onDone()
        }
    }
    BackHandler(enabled = state.isDirty(stableNewId)) { showDiscardConfirm = true }

    Scaffold(
        topBar = {
            RuleEditorTopBar(
                isEditing = ruleId != null,
                canDelete = state.existing != null,
                onBack = leave,
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
                error = error,
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

    if (showDiscardConfirm) {
        DiscardChangesDialog(
            onDismiss = { showDiscardConfirm = false },
            onConfirm = { showDiscardConfirm = false; onDone() },
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

    /** The draft exactly as it was loaded, for comparison -- see [isDirty]. */
    private var pristine by mutableStateOf<Rule?>(null)

    fun load(rule: Rule?) {
        existing = rule
        if (rule != null) applyExisting(rule)
        pristine = comparable(SNAPSHOT_ID)
        loaded = true
    }

    /**
     * Whether the user has changed anything since the screen opened.
     *
     * Compared as whole [Rule] values rather than field by field, so a
     * condition or schedule edit counts too; the id and the timestamps are
     * normalised away because [toRule] stamps `modifiedAt` with the current
     * instant, which would make every draft differ from itself.
     */
    fun isDirty(stableNewId: String): Boolean = loaded && comparable(stableNewId) != pristine

    private fun comparable(stableNewId: String): Rule =
        toRule(stableNewId).copy(id = SNAPSHOT_ID, createdAt = SNAPSHOT_TIME, modifiedAt = SNAPSHOT_TIME)

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

    fun isSaveable(violations: List<String>): Boolean = loaded && name.isNotBlank() && phenomena.isNotEmpty() && violations.isEmpty()

    private companion object {
        /** Stand-in id and timestamps, so a dirty-check compares the user's edits and nothing else. */
        const val SNAPSHOT_ID = "snapshot"
        val SNAPSHOT_TIME: Instant = Instant.fromEpochSeconds(0)
    }
}

/**
 * §9.5's caps, checked against the in-progress draft, plus the rule-set-wide
 * count (only relevant for a brand-new rule) and two guardrails the builder
 * itself can produce: an empty AND/OR group anywhere in the tree (not just
 * the root -- an empty `Cond.And` evaluates true, which can make an
 * enclosing OR match every occurrence of the selected phenomena) and an
 * empty location selection (an empty, non-null `locationIds` makes
 * `RuleEngine.matches` false for every location, i.e. the rule can never
 * fire, which is never what "not all locations" was meant to express).
 */
private fun ruleDraftViolations(state: RuleDraftState, existingRuleCount: Int): List<String> {
    if (!state.loaded) return emptyList()
    val violations = mutableListOf<String>()
    if (state.existing == null && existingRuleCount >= RuleLimits.MAX_RULES) {
        violations += "you already have ${RuleLimits.MAX_RULES} rules, the maximum"
    }
    if (hasEmptyGroup(state.conditionRoot)) {
        violations += "every group needs at least one condition or subgroup"
    }
    if (!state.useAllLocations && state.chosenLocationIds.isEmpty()) {
        violations += "pick at least one location, or switch to all saved locations"
    }
    violations += RuleLimits.violations(state.toRule("preview"))
    return violations
}

private fun hasEmptyGroup(node: ConditionNode): Boolean = when (node) {
    is ConditionNode.Group -> node.children.isEmpty() || node.children.any(::hasEmptyGroup)
    is ConditionNode.Leaf -> false
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
    error: String?,
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
        item { ErrorText(error) }
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

/** A save or delete that didn't happen -- [RuleEditorViewModel.error]. */
@Composable
private fun ErrorText(error: String?) {
    if (error == null) return
    Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun DeleteRuleDialog(ruleName: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    // Shared with desktop (`:core`'s deleteRuleConfirmation) so the same
    // action doesn't describe itself differently per platform.
    val copy = deleteRuleConfirmation(ruleName)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(copy.title) },
        text = { Text(copy.body) },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Delete") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun DiscardChangesDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Discard your changes?") },
        text = { Text("This rule has edits you haven't saved. Leaving now throws them away.") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Discard") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Keep editing") } },
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
