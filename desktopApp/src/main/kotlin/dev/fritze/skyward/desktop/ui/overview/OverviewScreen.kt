package dev.fritze.skyward.desktop.ui.overview

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import dev.fritze.skyward.core.format.phenomenonLabel
import dev.fritze.skyward.core.model.AuroraPayload
import dev.fritze.skyward.core.model.Phenomenon
import dev.fritze.skyward.core.model.SolarEclipsePayload
import dev.fritze.skyward.core.planner.UpcomingFilter
import dev.fritze.skyward.core.planner.UpcomingItem
import dev.fritze.skyward.core.planner.UpcomingScope
import dev.fritze.skyward.desktop.ui.DesktopAppState
import dev.fritze.skyward.desktop.ui.Destination
import dev.fritze.skyward.desktop.ui.common.formatDate
import dev.fritze.skyward.desktop.ui.common.formatDateTime
import dev.fritze.skyward.desktop.ui.common.formatKp
import dev.fritze.skyward.desktop.ui.common.formatPercent
import dev.fritze.skyward.desktop.ui.common.formatRelative
import dev.fritze.skyward.desktop.ui.common.rememberUpcoming
import dev.fritze.skyward.desktop.ui.eventdetail.EventDetailPane
import dev.fritze.skyward.desktop.ui.theme.kpColor
import dev.fritze.skyward.desktop.ui.theme.qualityColor
import dev.fritze.skyward.desktop.ui.theme.qualityLabel

/**
 * §14: "Overview = Upcoming list + mini aurora status + next-eclipse hero
 * card", two-pane with the shared event detail on the right.
 */
@Composable
fun OverviewScreen(state: DesktopAppState) {
    var filter by remember { mutableStateOf(UpcomingFilter()) }
    val upcoming = rememberUpcoming(state, filter)
    val refreshing by state.refreshingSources.collectAsState()
    val selected = state.selectedOccurrenceId

    Row(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Overview", style = MaterialTheme.typography.headlineSmall)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (refreshing.isNotEmpty()) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    TextButton(onClick = state::refreshEverything, enabled = refreshing.isEmpty()) { Text("Refresh") }
                }
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 20.dp, end = 20.dp, bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { HeroRow(state, upcoming.items) }
                item { FilterRow(filter, onChange = { filter = it }) }
                if (upcoming.isLoading && upcoming.items.isEmpty()) {
                    item {
                        Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.Center) {
                            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                        }
                    }
                } else if (upcoming.items.isEmpty()) {
                    item { EmptyState(state, filter.scope) }
                } else {
                    items(upcoming.items, key = { it.occurrence.id }) { item ->
                        UpcomingCard(state, item, isSelected = item.occurrence.id == selected)
                    }
                }
            }
        }

        if (selected != null) {
            VerticalDivider()
            Box(Modifier.width(420.dp).fillMaxHeight()) {
                EventDetailPane(state, selected, onClose = { state.selectOccurrence(null) })
            }
        }
    }
}

/** The two headline cards: next eclipse (hero) and current aurora status (mini). */
@Composable
private fun HeroRow(state: DesktopAppState, items: List<UpcomingItem>) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.weight(2f)) { NextEclipseHeroCard(state, items) }
        Box(Modifier.weight(1f)) { MiniAuroraCard(state) }
    }
}

@Composable
private fun NextEclipseHeroCard(state: DesktopAppState, items: List<UpcomingItem>) {
    val now by state.tick.collectAsState()
    // The hero deliberately reads the *unfiltered* eclipse set rather than
    // `items`: a filter chip that hides eclipses shouldn't empty the headline
    // card, it should only narrow the list under it.
    val occurrences by state.occurrences.collectAsState()
    val nextEclipse = occurrences
        .filter { it.phenomenon == Phenomenon.SOLAR_ECLIPSE || it.phenomenon == Phenomenon.LUNAR_ECLIPSE }
        .filter { (it.peakTime ?: it.window.end) >= now }
        .minByOrNull { it.peakTime ?: it.window.start }
    val visres = nextEclipse?.let { eclipse -> items.firstOrNull { it.occurrence.id == eclipse.id } }

    Card(
        modifier = Modifier.fillMaxWidth().clickable(enabled = nextEclipse != null) {
            nextEclipse?.let { state.selectOccurrence(it.id) }
        },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Next eclipse", style = MaterialTheme.typography.labelMedium)
            if (nextEclipse == null) {
                Text("No eclipse in the current horizon window.", style = MaterialTheme.typography.bodyMedium)
                return@Column
            }
            Text(nextEclipse.title, style = MaterialTheme.typography.headlineSmall)
            val anchor = nextEclipse.peakTime ?: nextEclipse.window.start
            Text("${formatDate(anchor, state.zone)} · ${formatRelative(now, anchor)}", style = MaterialTheme.typography.bodyMedium)
            val payload = nextEclipse.payload
            if (payload is SolarEclipsePayload) {
                Text(
                    "${payload.kind.name.lowercase().replaceFirstChar { it.uppercase() }} · " +
                        "${formatPercent(payload.obscurationAtGreatest)} at greatest eclipse",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (visres != null) {
                Text(
                    "At ${visres.bestLocation.name}: ${qualityLabel(visres.bestVisres.quality).lowercase()}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = qualityColor(visres.bestVisres.quality),
                )
            }
        }
    }
}

@Composable
private fun MiniAuroraCard(state: DesktopAppState) {
    val occurrences by state.occurrences.collectAsState()
    val now by state.tick.collectAsState()
    val grid by state.ovationGrid.collectAsState()

    val activeAurora = occurrences
        .filter { it.phenomenon == Phenomenon.AURORA && it.window.end >= now }
        .sortedBy { it.window.start }
    val maxKp = activeAurora.mapNotNull { (it.payload as? AuroraPayload)?.kpForecast }.maxOrNull()

    Card(
        modifier = Modifier.fillMaxWidth().clickable { state.navigateTo(Destination.AURORA) },
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Aurora", style = MaterialTheme.typography.labelMedium)
            if (maxKp == null) {
                Text("No forecast above your rule thresholds.", style = MaterialTheme.typography.bodyMedium)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(12.dp).clip(CircleShape).background(kpColor(maxKp)))
                    Text("Kp ${formatKp(maxKp)}", style = MaterialTheme.typography.headlineSmall)
                }
                Text("Peak forecast in the next 3 days", style = MaterialTheme.typography.bodySmall)
            }
            Text(
                grid?.let { "OVATION nowcast ${formatDateTime(it.forecastTime, state.zone)}" } ?: "No OVATION nowcast yet",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun FilterRow(filter: UpcomingFilter, onChange: (UpcomingFilter) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = filter.scope == UpcomingScope.MATCHED,
                onClick = { onChange(filter.copy(scope = UpcomingScope.MATCHED)) },
                label = { Text("Matched") },
            )
            FilterChip(
                selected = filter.scope == UpcomingScope.ALL,
                onClick = { onChange(filter.copy(scope = UpcomingScope.ALL)) },
                label = { Text("All") },
            )
        }
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (phenomenon in Phenomenon.entries) {
                FilterChip(
                    selected = phenomenon in filter.phenomena,
                    onClick = {
                        val next = if (phenomenon in filter.phenomena) filter.phenomena - phenomenon else filter.phenomena + phenomenon
                        onChange(filter.copy(phenomena = next))
                    },
                    label = { Text(phenomenonLabel(phenomenon)) },
                )
            }
        }
    }
}

@Composable
private fun UpcomingCard(state: DesktopAppState, item: UpcomingItem, isSelected: Boolean) {
    val now by state.tick.collectAsState()
    val anchor = item.occurrence.peakTime ?: item.occurrence.window.start
    Card(
        modifier = Modifier.fillMaxWidth().clickable { state.selectOccurrence(item.occurrence.id) },
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface,
        ),
    ) {
        Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(qualityColor(item.bestVisres.quality)))
            Column(Modifier.weight(1f)) {
                Text(item.occurrence.title, style = MaterialTheme.typography.titleSmall)
                Text(
                    "${phenomenonLabel(item.occurrence.phenomenon)} · ${formatDateTime(anchor, state.zone)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (item.matchedRuleNames.isNotEmpty()) {
                    Text(
                        item.matchedRuleNames.joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(formatRelative(now, anchor), style = MaterialTheme.typography.labelMedium)
                Text(
                    "${qualityLabel(item.bestVisres.quality)} · ${item.bestLocation.name}",
                    style = MaterialTheme.typography.labelSmall,
                    color = qualityColor(item.bestVisres.quality),
                )
            }
        }
    }
}

@Composable
private fun EmptyState(state: DesktopAppState, scope: UpcomingScope) {
    val locations by state.locations.collectAsState()
    Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalDivider()
        if (locations.isEmpty()) {
            Text("No saved locations yet.", style = MaterialTheme.typography.titleSmall)
            Text(
                "Visibility is always computed for a place. Add one in Settings to see what is coming up.",
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = { state.navigateTo(Destination.SETTINGS) }) { Text("Open Settings") }
        } else if (scope == UpcomingScope.MATCHED) {
            Text("Nothing matches your rules right now.", style = MaterialTheme.typography.titleSmall)
            Text("Switch to \"All\" to see everything in the horizon window.", style = MaterialTheme.typography.bodyMedium)
        } else {
            Text("Nothing in the horizon window yet.", style = MaterialTheme.typography.titleSmall)
            Text("Refresh to compute events for the next few years.", style = MaterialTheme.typography.bodyMedium)
        }
    }
}
