package dev.fritze.skyward.desktop.ui.rules

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
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
import dev.fritze.skyward.core.format.deleteRuleConfirmation
import dev.fritze.skyward.core.model.Phenomenon
import dev.fritze.skyward.core.planner.Planner
import dev.fritze.skyward.core.rules.Anchor
import dev.fritze.skyward.core.rules.Cond
import dev.fritze.skyward.core.rules.NotifySchedule
import dev.fritze.skyward.core.rules.QuietHours
import dev.fritze.skyward.core.rules.Rule
import dev.fritze.skyward.core.rules.RuleLimits
import dev.fritze.skyward.desktop.ui.DesktopAppState
import dev.fritze.skyward.desktop.ui.common.Dropdown
import dev.fritze.skyward.desktop.ui.common.NumberField
import dev.fritze.skyward.desktop.ui.common.SectionCard
import dev.fritze.skyward.desktop.ui.common.phenomenonLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * §14: the Rules screen, desktop two-pane — list on the left, the §13.4
 * structured builder on the right. Same `:core` contract as Android: rules
 * are `Rule`/`Cond`/`NotifySchedule` values, validated by [RuleLimits] and
 * previewed through `Planner.computeMatches`.
 */
@Composable
fun RulesScreen(state: DesktopAppState) {
    val rules by state.visibleRules.collectAsState()
    val allRules by state.allRules.collectAsState()
    var editingId by remember { mutableStateOf<String?>(null) }
    var draft by remember { mutableStateOf<Rule?>(null) }

    Row(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text("Rules", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "${allRules.size} of ${RuleLimits.MAX_RULES} rules (including hidden mutes)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Button(
                    onClick = {
                        val fresh = newRule()
                        draft = fresh
                        editingId = fresh.id
                    },
                    enabled = allRules.size < RuleLimits.MAX_RULES,
                ) { Text(NEW_RULE_TITLE) }
            }

            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 20.dp, end = 20.dp, bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (rules.isEmpty()) {
                    item { Text("No rules yet — the shipped defaults are created on first launch.", style = MaterialTheme.typography.bodyMedium) }
                }
                items(rules, key = { it.id }) { rule ->
                    RuleRow(
                        state = state,
                        rule = rule,
                        isSelected = rule.id == editingId,
                        onEdit = {
                            draft = rule
                            editingId = rule.id
                        },
                    )
                }
            }
        }

        val editing = draft
        if (editing != null) {
            VerticalDivider()
            Box(Modifier.width(520.dp).fillMaxHeight()) {
                RuleEditorPane(
                    state = state,
                    draft = editing,
                    onDraftChange = { draft = it },
                    existing = rules.any { it.id == editing.id },
                    onClose = {
                        draft = null
                        editingId = null
                    },
                )
            }
        }
    }
}

@Composable
private fun RuleRow(state: DesktopAppState, rule: Rule, isSelected: Boolean, onEdit: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onEdit),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface,
        ),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text(rule.name, style = MaterialTheme.typography.titleSmall)
                Text(
                    rule.phenomena.joinToString(", ") { phenomenonLabel(it) }.ifEmpty { "No phenomena selected" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(describeCondition(rule.condition), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(
                checked = rule.enabled,
                onCheckedChange = { enabled ->
                    state.launch {
                        state.container.ruleRepo.setEnabled(rule.id, enabled, Clock.System.now())
                        state.container.replan()
                    }
                },
            )
        }
    }
}

@Composable
private fun RuleEditorPane(
    state: DesktopAppState,
    draft: Rule,
    onDraftChange: (Rule) -> Unit,
    existing: Boolean,
    onClose: () -> Unit,
) {
    val violations = remember(draft) { RuleLimits.violations(draft) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(if (existing) "Edit rule" else NEW_RULE_TITLE, style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = onClose) { Text("Close") }
        }

        OutlinedTextField(
            value = draft.name,
            onValueChange = { onDraftChange(draft.copy(name = it)) },
            label = { Text("Name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        PhenomenaSection(draft, onDraftChange)
        LocationsSection(state, draft, onDraftChange)

        SectionCard("Condition") {
            ConditionEditor(draft.condition, onChange = { onDraftChange(draft.copy(condition = it)) })
        }

        ScheduleEditor(draft.schedule) { onDraftChange(draft.copy(schedule = it)) }

        LivePreviewCard(state, draft)

        if (violations.isNotEmpty()) {
            Text(violations.joinToString("\n"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }

        EditorActions(state, draft, existing, canSave = violations.isEmpty(), onClose = onClose)
    }
}

@Composable
private fun PhenomenaSection(draft: Rule, onDraftChange: (Rule) -> Unit) {
    SectionCard("Phenomena") {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (row in Phenomenon.entries.chunked(3)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (phenomenon in row) {
                        FilterChip(
                            selected = phenomenon in draft.phenomena,
                            onClick = { onDraftChange(draft.copy(phenomena = draft.phenomena.toggle(phenomenon))) },
                            label = { Text(phenomenonLabel(phenomenon)) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LocationsSection(state: DesktopAppState, draft: Rule, onDraftChange: (Rule) -> Unit) {
    val locations by state.locations.collectAsState()
    SectionCard("Locations") {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(
                selected = draft.locationIds == null,
                onClick = { onDraftChange(draft.copy(locationIds = null)) },
                label = { Text("All saved locations") },
            )
            for (location in locations) {
                val selected = draft.locationIds?.contains(location.id) == true
                FilterChip(
                    selected = selected,
                    onClick = {
                        val current = draft.locationIds.orEmpty()
                        val next = if (selected) current - location.id else current + location.id
                        // null means "all locations" (§9.1), so an empty
                        // selection must collapse back to that rather than
                        // becoming a rule that matches nowhere.
                        onDraftChange(draft.copy(locationIds = next.ifEmpty { null }))
                    },
                    label = { Text(location.name) },
                )
            }
        }
    }
}

@Composable
private fun EditorActions(state: DesktopAppState, draft: Rule, existing: Boolean, canSave: Boolean, onClose: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(
            onClick = {
                state.launch {
                    // The editor has no enabled switch — the list row owns it —
                    // so the draft's copy is a snapshot from when the pane
                    // opened. Re-read it, or saving silently undoes a toggle
                    // made in the list while the editor was open.
                    val storedEnabled = state.container.ruleRepo.getAll().firstOrNull { it.id == draft.id }?.enabled
                    state.container.ruleRepo.upsert(
                        draft.copy(enabled = storedEnabled ?: draft.enabled, modifiedAt = Clock.System.now()),
                    )
                    state.container.replan()
                }
                onClose()
            },
            enabled = canSave && draft.phenomena.isNotEmpty() && draft.name.isNotBlank(),
        ) { Text("Save") }
        if (existing) {
            var confirmDelete by remember { mutableStateOf(false) }
            OutlinedButton(onClick = { confirmDelete = true }) { Text("Delete") }
            // Android has always confirmed this; desktop deleted the rule and
            // cancelled its reminders on one click. Same action, same
            // consequence, so now the same dialog copy from `:core`.
            if (confirmDelete) {
                val copy = deleteRuleConfirmation(draft.name)
                AlertDialog(
                    onDismissRequest = { confirmDelete = false },
                    title = { Text(copy.title) },
                    text = { Text(copy.body) },
                    confirmButton = {
                        TextButton(onClick = {
                            confirmDelete = false
                            state.launch {
                                state.container.ruleRepo.delete(draft.id)
                                state.container.replan()
                            }
                            onClose()
                        }) { Text("Delete") }
                    },
                    dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
                )
            }
        }
    }
}

private val LEAD_PRESETS: List<Duration> = listOf(30.days, 14.days, 7.days, 2.days, 1.days, 6.hours, 2.hours, 30.minutes)

@Composable
private fun ScheduleEditor(schedule: NotifySchedule, onChange: (NotifySchedule) -> Unit) {
    SectionCard("When to notify") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Leads before the anchor", style = MaterialTheme.typography.labelMedium)
            LeadChips(schedule, onChange)

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Anchor")
                Dropdown(schedule.anchor, Anchor.entries, { it.describe() }) { onChange(schedule.copy(anchor = it)) }
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Switch(schedule.notifyOnFirstSeen, { onChange(schedule.copy(notifyOnFirstSeen = it)) })
                Text("Notify as soon as it first matches")
            }

            QuietHoursRow(schedule, onChange)
        }
    }
}

@Composable
private fun LeadChips(schedule: NotifySchedule, onChange: (NotifySchedule) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (row in LEAD_PRESETS.chunked(4)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (lead in row) {
                    FilterChip(
                        selected = lead in schedule.leads,
                        onClick = {
                            // Descending so the furthest-out reminder is first,
                            // matching how §9.6's shipped rules read.
                            val next = if (lead in schedule.leads) schedule.leads - lead else (schedule.leads + lead).sortedDescending()
                            onChange(schedule.copy(leads = next))
                        },
                        label = { Text(describeLead(lead)) },
                    )
                }
            }
        }
    }
}

@Composable
private fun QuietHoursRow(schedule: NotifySchedule, onChange: (NotifySchedule) -> Unit) {
    val quiet = schedule.quietHours
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Switch(
            checked = quiet != null,
            onCheckedChange = { on -> onChange(schedule.copy(quietHours = if (on) DEFAULT_QUIET_HOURS else null)) },
        )
        Text("Quiet hours")
        if (quiet != null) {
            NumberField(quiet.fromHour.toDouble(), { onChange(schedule.copy(quietHours = quiet.copy(fromHour = it.toHourOfDay()))) }, decimals = false)
            Text("to")
            NumberField(quiet.toHour.toDouble(), { onChange(schedule.copy(quietHours = quiet.copy(toHour = it.toHourOfDay()))) }, decimals = false)
        }
    }
}

private val DEFAULT_QUIET_HOURS = QuietHours(23, 7)

private fun Double.toHourOfDay(): Int = toInt().coerceIn(0, 23)

private fun <T> Set<T>.toggle(value: T): Set<T> = if (value in this) this - value else this + value

/**
 * §13.4's live preview: "matches N upcoming events — run the engine on the
 * fly against current DB occurrences (debounce 500 ms)".
 */
@Composable
private fun LivePreviewCard(state: DesktopAppState, draft: Rule) {
    val occurrences by state.occurrences.collectAsState()
    val locations by state.locations.collectAsState()
    val now by state.tick.collectAsState()
    var count by remember { mutableStateOf<Int?>(null) }
    var computing by remember { mutableStateOf(false) }

    val previewable = draft.phenomena.isNotEmpty()
    LaunchedEffect(draft.phenomena, draft.locationIds, draft.condition, occurrences, locations, previewable) {
        if (!previewable) {
            count = null
            return@LaunchedEffect
        }
        computing = true
        try {
            delay(PREVIEW_DEBOUNCE_MS)
            val ctx = state.visibilityContext(now)
            count = withContext(Dispatchers.Default) {
                Planner.computeMatches(occurrences, locations, listOf(draft.copy(enabled = true)), state.container.visibilityModels, ctx)
                    .distinctBy { it.occ.id }
                    .size
            }
        } finally {
            // Every keystroke cancels this effect mid-debounce; without the
            // `finally` the spinner from the cancelled run would be left
            // spinning forever once the user stopped typing.
            computing = false
        }
    }

    SectionCard("Live preview") {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (computing) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
            Text(
                when {
                    !previewable -> "Pick at least one phenomenon to preview matches."
                    count == null -> "Computing…"
                    else -> "Matches $count upcoming event${if (count == 1) "" else "s"}"
                },
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (draft.phenomena.any { it == Phenomenon.AURORA || it == Phenomenon.COMET || it == Phenomenon.TERRESTRIAL }) {
            HorizontalDivider()
            Text(
                "Forecast-based phenomena preview against current and future data only — past matches can't be shown.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private const val PREVIEW_DEBOUNCE_MS = 500L

/** Shown on the button, as the editor's heading, and as a fresh rule's starting name. */
private const val NEW_RULE_TITLE = "New rule"

private fun newRule(): Rule {
    val now = Clock.System.now()
    return Rule(
        id = UUID.randomUUID().toString(),
        name = NEW_RULE_TITLE,
        enabled = true,
        phenomena = emptySet(),
        locationIds = null,
        condition = Cond.And(emptyList()),
        schedule = NotifySchedule(leads = listOf(1.days), anchor = Anchor.PEAK, notifyOnFirstSeen = false, quietHours = null),
        hidden = false,
        createdAt = now,
        modifiedAt = now,
    )
}

private fun Anchor.describe() = when (this) {
    Anchor.PEAK -> "peak time"
    Anchor.WINDOW_START -> "window start"
    Anchor.BEST_VIEWING -> "best viewing time"
}

internal fun describeLead(lead: Duration): String = when {
    lead.inWholeDays >= 1 -> "${lead.inWholeDays} d"
    lead.inWholeHours >= 1 -> "${lead.inWholeHours} h"
    else -> "${lead.inWholeMinutes} min"
}

/** One-line human summary of a condition tree, for the list rows. */
internal fun describeCondition(condition: Cond): String = when (condition) {
    is Cond.And -> if (condition.all.isEmpty()) "Always matches" else condition.all.joinToString(" and ") { describeCondition(it) }
    is Cond.Or -> condition.any.joinToString(" or ") { describeCondition(it) }
    is Cond.Not -> "not (${describeCondition(condition.inner)})"
    is Cond.VisibleAtLocation -> "visible (${condition.minQuality.name.lowercase()}+)"
    is Cond.ReachableWithin -> "within ${condition.km.toInt()} km (${condition.minQualityThere.name.lowercase()}+)"
    is Cond.KpAtLeast -> "Kp ≥ ${condition.kp}"
    is Cond.ZhrAtLeast -> "ZHR ≥ ${condition.zhr}"
    is Cond.MagnitudeAtMost -> "mag ≤ ${condition.mag}"
    is Cond.EclipseKindIn -> condition.kinds.joinToString("/") { it.name.lowercase() }
    is Cond.LunarKindIn -> condition.kinds.joinToString("/") { it.name.lowercase() }
    is Cond.MoonIlluminationAtMost -> "moon ≤ ${(condition.fraction * 100).toInt()} %"
    is Cond.EonetCategoryIn -> condition.categoryIds.joinToString("/")
    is Cond.CertaintyIs -> condition.certainty.name.lowercase()
    is Cond.AuroraKindIs -> condition.kind.name.lowercase()
    is Cond.OccurrenceIdIs -> "this event only"
    is Cond.PeakInDaysAhead -> "within ${condition.maxDays} days"
    is Cond.PeakOnWeekend -> "on a weekend"
    is Cond.PeakInLocalHours -> "${condition.fromHour}:00–${condition.toHour}:00 local"
}
