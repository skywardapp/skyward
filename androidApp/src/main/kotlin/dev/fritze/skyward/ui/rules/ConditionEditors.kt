package dev.fritze.skyward.ui.rules

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.fritze.skyward.core.model.AuroraForecastKind
import dev.fritze.skyward.core.model.Certainty
import dev.fritze.skyward.core.model.LunarEclipseKind
import dev.fritze.skyward.core.model.Phenomenon
import dev.fritze.skyward.core.model.Quality
import dev.fritze.skyward.core.model.SolarEclipseKind
import dev.fritze.skyward.core.rules.Cond
import kotlin.math.roundToInt

// ---- generic field widgets --------------------------------------------------------------

@Composable
fun LabeledIntSlider(label: String, value: Int, range: IntRange, onChange: (Int) -> Unit) {
    Column {
        Text("$label: $value", style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.roundToInt().coerceIn(range)) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = (range.last - range.first - 1).coerceAtLeast(0),
        )
    }
}

@Composable
fun LabeledDoubleSlider(label: String, value: Double, range: ClosedFloatingPointRange<Double>, onChange: (Double) -> Unit) {
    Column {
        Text("$label: ${"%.1f".format(value)}", style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.toDouble().coerceIn(range)) },
            valueRange = range.start.toFloat()..range.endInclusive.toFloat(),
        )
    }
}

@Composable
fun <T> EnumDropdown(label: String, value: T, options: List<T>, labelFor: (T) -> String, onChange: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) { Text("$label: ${labelFor(value)}") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            for (option in options) {
                DropdownMenuItem(text = { Text(labelFor(option)) }, onClick = { onChange(option); expanded = false })
            }
        }
    }
}

@Composable
fun <T> MultiChipRow(label: String, options: List<T>, selected: Set<T>, labelFor: (T) -> String, onToggle: (T) -> Unit) {
    Column {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (option in options) {
                FilterChip(selected = option in selected, onClick = { onToggle(option) }, label = { Text(labelFor(option)) })
            }
        }
    }
}

private fun qualityLabel(q: Quality) = q.name.lowercase().replaceFirstChar { it.uppercase() }
private fun certaintyLabel(c: Certainty) = c.name.lowercase().replaceFirstChar { it.uppercase() }
private fun eclipseKindLabel(k: SolarEclipseKind) = k.name.lowercase().replaceFirstChar { it.uppercase() }
private fun lunarKindLabel(k: LunarEclipseKind) = k.name.lowercase().replaceFirstChar { it.uppercase() }
private fun auroraKindLabel(k: AuroraForecastKind) = if (k == AuroraForecastKind.NOWCAST) "Now" else "3-day outlook"
private fun eonetCategoryLabel(id: String) = EONET_CATEGORIES.firstOrNull { it.first == id }?.second ?: id

/** §13.4: "typed inputs (sliders for km/Kp/ZHR/mag with sensible ranges, quality dropdowns)." */
@Composable
fun CondFieldsEditor(cond: Cond, onChange: (Cond) -> Unit) {
    when (cond) {
        is Cond.VisibleAtLocation ->
            EnumDropdown("Minimum quality", cond.minQuality, Quality.entries, ::qualityLabel) { onChange(cond.copy(minQuality = it)) }

        is Cond.ReachableWithin -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            LabeledDoubleSlider("Travel distance (km)", cond.km, 0.0..2000.0) { onChange(cond.copy(km = it)) }
            EnumDropdown("Minimum quality there", cond.minQualityThere, Quality.entries, ::qualityLabel) { onChange(cond.copy(minQualityThere = it)) }
        }

        is Cond.KpAtLeast -> LabeledDoubleSlider("Kp index at least", cond.kp, 0.0..9.0) { onChange(cond.copy(kp = it)) }
        is Cond.ZhrAtLeast -> LabeledIntSlider("ZHR at least", cond.zhr, 0..300) { onChange(cond.copy(zhr = it)) }
        is Cond.MagnitudeAtMost -> LabeledDoubleSlider("Magnitude at most (lower = brighter)", cond.mag, -5.0..15.0) { onChange(cond.copy(mag = it)) }
        is Cond.MoonIlluminationAtMost -> LabeledDoubleSlider("Moon illumination at most", cond.fraction, 0.0..1.0) { onChange(cond.copy(fraction = it)) }
        is Cond.PeakInDaysAhead -> LabeledIntSlider("Within N days", cond.maxDays, 1..365) { onChange(cond.copy(maxDays = it)) }

        is Cond.EclipseKindIn ->
            MultiChipRow("Kinds", SolarEclipseKind.entries, cond.kinds, ::eclipseKindLabel) { kind ->
                onChange(cond.copy(kinds = if (kind in cond.kinds) cond.kinds - kind else cond.kinds + kind))
            }
        is Cond.LunarKindIn ->
            MultiChipRow("Kinds", LunarEclipseKind.entries, cond.kinds, ::lunarKindLabel) { kind ->
                onChange(cond.copy(kinds = if (kind in cond.kinds) cond.kinds - kind else cond.kinds + kind))
            }
        is Cond.EonetCategoryIn ->
            MultiChipRow("Categories", EONET_CATEGORIES.map { it.first }, cond.categoryIds, ::eonetCategoryLabel) { id ->
                onChange(cond.copy(categoryIds = if (id in cond.categoryIds) cond.categoryIds - id else cond.categoryIds + id))
            }

        is Cond.CertaintyIs -> EnumDropdown("Certainty", cond.certainty, Certainty.entries, ::certaintyLabel) { onChange(cond.copy(certainty = it)) }
        is Cond.AuroraKindIs -> EnumDropdown("Forecast kind", cond.kind, AuroraForecastKind.entries, ::auroraKindLabel) { onChange(cond.copy(kind = it)) }

        is Cond.PeakOnWeekend -> WeekendCheckbox(cond.includeFridayNight) { onChange(cond.copy(includeFridayNight = it)) }
        is Cond.PeakInLocalHours -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            LabeledIntSlider("From hour", cond.fromHour, 0..23) { onChange(cond.copy(fromHour = it)) }
            LabeledIntSlider("To hour", cond.toHour, 0..23) { onChange(cond.copy(toHour = it)) }
        }

        // Cond.And/Or/Not/OccurrenceIdIs never appear as a leaf in this builder (§13.4's own note
        // that groups, not per-row inversion, are how And/Or/Not are expressed; OccurrenceIdIs is
        // hidden-rule-only, §13.3) -- nothing to render.
        else -> Unit
    }
}

// ---- tree editor -------------------------------------------------------------------------

@Composable
fun ConditionGroupEditor(
    node: ConditionNode.Group,
    phenomena: Set<Phenomenon>,
    depth: Int,
    onChange: (ConditionNode.Group) -> Unit,
    onDeleteSelf: (() -> Unit)?,
) {
    var showAddMenu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val availableTypes = remember(phenomena) { COND_TYPE_OPTIONS.filter { it.appliesTo(phenomena) } }

    Card(
        modifier = Modifier.fillMaxWidth().padding(start = (depth * 12).dp),
        colors = CardDefaults.cardColors(containerColor = if (depth % 2 == 0) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = node.op == GroupOp.AND, onClick = { onChange(node.copy(op = GroupOp.AND)) }, label = { Text("AND") })
                    FilterChip(selected = node.op == GroupOp.OR, onClick = { onChange(node.copy(op = GroupOp.OR)) }, label = { Text("OR") })
                }
                if (onDeleteSelf != null) {
                    IconButton(onClick = { if (node.children.isEmpty()) onDeleteSelf() else confirmDelete = true }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Delete group")
                    }
                }
            }

            for ((index, child) in node.children.withIndex()) {
                when (child) {
                    is ConditionNode.Group -> ConditionGroupEditor(
                        node = child,
                        phenomena = phenomena,
                        depth = depth + 1,
                        onChange = { updated -> onChange(node.copy(children = node.children.toMutableList().apply { set(index, updated) })) },
                        onDeleteSelf = { onChange(node.copy(children = node.children.toMutableList().apply { removeAt(index) })) },
                    )
                    is ConditionNode.Leaf -> ConditionLeafEditor(
                        leaf = child,
                        onChange = { updated -> onChange(node.copy(children = node.children.toMutableList().apply { set(index, updated) })) },
                        onDelete = { onChange(node.copy(children = node.children.toMutableList().apply { removeAt(index) })) },
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box {
                    TextButton(onClick = { showAddMenu = true }, enabled = availableTypes.isNotEmpty()) { Text("Add condition") }
                    DropdownMenu(expanded = showAddMenu, onDismissRequest = { showAddMenu = false }) {
                        for (type in availableTypes) {
                            DropdownMenuItem(
                                text = { Text(type.label) },
                                onClick = {
                                    onChange(node.copy(children = node.children + ConditionNode.Leaf(type.default())))
                                    showAddMenu = false
                                },
                            )
                        }
                    }
                }
                TextButton(onClick = { onChange(node.copy(children = node.children + ConditionNode.Group(GroupOp.AND, emptyList()))) }) {
                    Text("Add group")
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this group?") },
            text = { Text("This group has ${node.children.size} condition${if (node.children.size == 1) "" else "s"} inside it. Deleting it removes all of them.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDeleteSelf?.invoke() }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ConditionLeafEditor(leaf: ConditionNode.Leaf, onChange: (ConditionNode.Leaf) -> Unit, onDelete: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(condTypeLabel(leaf.cond), style = MaterialTheme.typography.titleSmall)
                CondFieldsEditor(leaf.cond) { onChange(ConditionNode.Leaf(it)) }
            }
            IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "Delete condition") }
        }
    }
}
