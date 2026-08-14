package dev.fritze.skyward.desktop.ui.rules

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.fritze.skyward.core.model.AuroraForecastKind
import dev.fritze.skyward.core.model.Certainty
import dev.fritze.skyward.core.model.LunarEclipseKind
import dev.fritze.skyward.core.model.Quality
import dev.fritze.skyward.core.model.SolarEclipseKind
import dev.fritze.skyward.core.rules.Cond
import dev.fritze.skyward.desktop.ui.common.Dropdown
import dev.fritze.skyward.desktop.ui.common.NumberField

/**
 * §13.4's structured condition builder, desktop layout. Operates directly on
 * the immutable [Cond] AST (§9.1) with functional updates rather than the
 * mutable node tree the Android editor keeps — a mouse-driven tree can
 * rebuild the whole condition on every edit without the churn that would
 * cause on a touch keyboard.
 *
 * §9.4 applies here as much as it does to the AST: what isn't in [Cond]
 * isn't offered, and this editor never invents a condition kind.
 */
@Composable
fun ConditionEditor(
    condition: Cond,
    onChange: (Cond) -> Unit,
    modifier: Modifier = Modifier,
    onRemove: (() -> Unit)? = null,
    depth: Int = 0,
) {
    when (condition) {
        is Cond.And -> GroupEditor(
            group = ConditionGroup(
                title = "All of",
                switchLabel = "Any of",
                children = condition.all,
                onChildrenChange = { onChange(Cond.And(it)) },
                onSwitchKind = { onChange(Cond.Or(condition.all)) },
            ),
            onRemove = onRemove,
            depth = depth,
            modifier = modifier,
        )
        is Cond.Or -> GroupEditor(
            group = ConditionGroup(
                title = "Any of",
                switchLabel = "All of",
                children = condition.any,
                onChildrenChange = { onChange(Cond.Or(it)) },
                onSwitchKind = { onChange(Cond.And(condition.any)) },
            ),
            onRemove = onRemove,
            depth = depth,
            modifier = modifier,
        )
        is Cond.Not -> OutlinedCard(modifier.fillMaxWidth()) {
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Not", style = MaterialTheme.typography.labelLarge)
                    TextButton(onClick = { onChange(condition.inner) }) { Text("Drop the NOT") }
                    if (onRemove != null) TextButton(onClick = onRemove) { Text("Remove") }
                }
                ConditionEditor(condition.inner, onChange = { onChange(Cond.Not(it)) }, depth = depth + 1)
            }
        }
        else -> LeafEditor(condition, onChange, onRemove, modifier)
    }
}

/** An AND or OR node, in the one shape [GroupEditor] needs — the two differ only in wording and rebuild function. */
private data class ConditionGroup(
    val title: String,
    val switchLabel: String,
    val children: List<Cond>,
    val onChildrenChange: (List<Cond>) -> Unit,
    val onSwitchKind: () -> Unit,
)

@Composable
private fun GroupEditor(
    group: ConditionGroup,
    onRemove: (() -> Unit)?,
    depth: Int,
    modifier: Modifier,
) {
    OutlinedCard(modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(group.title, style = MaterialTheme.typography.labelLarge)
                TextButton(onClick = group.onSwitchKind) { Text("Switch to \"${group.switchLabel}\"") }
                if (onRemove != null) TextButton(onClick = onRemove) { Text("Remove") }
            }
            group.children.forEachIndexed { index, child ->
                ConditionEditor(
                    condition = child,
                    onChange = { updated -> group.onChildrenChange(group.children.toMutableList().also { it[index] = updated }) },
                    onRemove = { group.onChildrenChange(group.children.filterIndexed { i, _ -> i != index }) },
                    depth = depth + 1,
                )
            }
            AddConditionMenu(onAdd = { group.onChildrenChange(group.children + it) })
        }
    }
}

/** The palette of leaf conditions — one entry per §9.1 `Cond` subtype the user can author. */
private val CONDITION_TEMPLATES: List<Pair<String, Cond>> = listOf(
    "Visible at the location" to Cond.VisibleAtLocation(),
    "Reachable within a distance" to Cond.ReachableWithin(km = 500.0),
    "Kp at least" to Cond.KpAtLeast(5.0),
    "ZHR at least" to Cond.ZhrAtLeast(50),
    "Comet magnitude at most" to Cond.MagnitudeAtMost(6.0),
    "Solar eclipse kind is one of" to Cond.EclipseKindIn(setOf(SolarEclipseKind.TOTAL)),
    "Lunar eclipse kind is one of" to Cond.LunarKindIn(setOf(LunarEclipseKind.TOTAL)),
    "Moon illumination at most" to Cond.MoonIlluminationAtMost(0.4),
    "EONET category is one of" to Cond.EonetCategoryIn(setOf("volcanoes")),
    "Certainty is" to Cond.CertaintyIs(Certainty.CERTAIN),
    "Aurora forecast kind is" to Cond.AuroraKindIs(AuroraForecastKind.NOWCAST),
    "Peak within N days" to Cond.PeakInDaysAhead(30),
    "Peak on a weekend" to Cond.PeakOnWeekend(),
    "Peak in local hours" to Cond.PeakInLocalHours(fromHour = 22, toHour = 6),
    "All of…" to Cond.And(emptyList()),
    "Any of…" to Cond.Or(emptyList()),
    "Not…" to Cond.Not(Cond.VisibleAtLocation()),
)

@Composable
private fun AddConditionMenu(onAdd: (Cond) -> Unit) {
    Dropdown(
        selected = ADD_PLACEHOLDER,
        options = listOf(ADD_PLACEHOLDER) + CONDITION_TEMPLATES.map { it.first },
        label = { it },
        onSelect = { chosen ->
            CONDITION_TEMPLATES.firstOrNull { it.first == chosen }?.let { onAdd(it.second) }
        },
    )
}

private const val ADD_PLACEHOLDER = "Add condition…"

@Composable
private fun LeafEditor(condition: Cond, onChange: (Cond) -> Unit, onRemove: (() -> Unit)?, modifier: Modifier) {
    OutlinedCard(modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(10.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                when (condition) {
                    is Cond.VisibleAtLocation -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Visible at the location, at least")
                        QualityDropdown(condition.minQuality) { onChange(condition.copy(minQuality = it)) }
                    }
                    is Cond.ReachableWithin -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Reachable within")
                        NumberField(condition.km, { onChange(condition.copy(km = it)) }, label = "km")
                        Text("reaching at least")
                        QualityDropdown(condition.minQualityThere) { onChange(condition.copy(minQualityThere = it)) }
                    }
                    is Cond.KpAtLeast -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Kp at least")
                        NumberField(condition.kp, { onChange(condition.copy(kp = it)) })
                    }
                    is Cond.ZhrAtLeast -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("ZHR at least")
                        NumberField(condition.zhr.toDouble(), { onChange(condition.copy(zhr = it.toInt())) }, decimals = false)
                    }
                    is Cond.MagnitudeAtMost -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Comet magnitude at most")
                        NumberField(condition.mag, { onChange(condition.copy(mag = it)) })
                    }
                    is Cond.EclipseKindIn -> KindChips(
                        label = "Solar eclipse kind",
                        all = SolarEclipseKind.entries,
                        selected = condition.kinds,
                        onToggle = { onChange(condition.copy(kinds = condition.kinds.toggle(it))) },
                    )
                    is Cond.LunarKindIn -> KindChips(
                        label = "Lunar eclipse kind",
                        all = LunarEclipseKind.entries,
                        selected = condition.kinds,
                        onToggle = { onChange(condition.copy(kinds = condition.kinds.toggle(it))) },
                    )
                    is Cond.MoonIlluminationAtMost -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Moon illumination at most (0–1)")
                        NumberField(condition.fraction, { onChange(condition.copy(fraction = it.coerceIn(0.0, 1.0))) })
                    }
                    is Cond.EonetCategoryIn -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("EONET categories (comma separated)")
                        OutlinedTextField(
                            value = condition.categoryIds.joinToString(","),
                            onValueChange = { raw ->
                                onChange(condition.copy(categoryIds = raw.split(",").map(String::trim).filter(String::isNotEmpty).toSet()))
                            },
                            singleLine = true,
                        )
                    }
                    is Cond.CertaintyIs -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Certainty is")
                        Dropdown(condition.certainty, Certainty.entries, { entry -> entry.name.lowercase() }) { onChange(condition.copy(certainty = it)) }
                    }
                    is Cond.AuroraKindIs -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Aurora forecast kind is")
                        Dropdown(condition.kind, AuroraForecastKind.entries, { entry -> entry.name.lowercase() }) { onChange(condition.copy(kind = it)) }
                    }
                    is Cond.PeakInDaysAhead -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Peak within the next")
                        NumberField(condition.maxDays.toDouble(), { onChange(condition.copy(maxDays = it.toInt())) }, label = "days", decimals = false)
                    }
                    is Cond.PeakOnWeekend -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Peak on a weekend")
                        FilterChip(
                            selected = condition.includeFridayNight,
                            onClick = { onChange(condition.copy(includeFridayNight = !condition.includeFridayNight)) },
                            label = { Text("include Friday night") },
                        )
                    }
                    is Cond.PeakInLocalHours -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Peak between local hours")
                        NumberField(condition.fromHour.toDouble(), { onChange(condition.copy(fromHour = it.toInt().coerceIn(0, 23))) }, decimals = false)
                        Text("and")
                        NumberField(condition.toHour.toDouble(), { onChange(condition.copy(toHour = it.toInt().coerceIn(0, 23))) }, decimals = false)
                    }
                    is Cond.OccurrenceIdIs -> Text("This specific event (${condition.id})")
                    // And/Or/Not are handled by ConditionEditor before reaching here.
                    is Cond.And, is Cond.Or, is Cond.Not -> Text("(group)")
                }
            }
            if (onRemove != null) TextButton(onClick = onRemove) { Text("Remove") }
        }
    }
}

@Composable
private fun QualityDropdown(selected: Quality, onSelect: (Quality) -> Unit) {
    Dropdown(selected, Quality.entries, { it.name.lowercase() }, onSelect = onSelect)
}

@Composable
private fun <T : Enum<T>> KindChips(label: String, all: List<T>, selected: Set<T>, onToggle: (T) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (kind in all) {
                FilterChip(
                    selected = kind in selected,
                    onClick = { onToggle(kind) },
                    label = { Text(kind.name.lowercase()) },
                )
            }
        }
    }
}

private fun <T> Set<T>.toggle(value: T): Set<T> = if (value in this) this - value else this + value
