package dev.fritze.skyward.ui.upcoming

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.fritze.skyward.core.model.Occurrence
import dev.fritze.skyward.core.model.Phenomenon
import dev.fritze.skyward.core.model.Quality
import dev.fritze.skyward.core.planner.UpcomingItem
import dev.fritze.skyward.core.planner.UpcomingScope
import dev.fritze.skyward.data.AppContainer
import dev.fritze.skyward.ui.common.phenomenonLabel
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.math.roundToInt
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpcomingScreen(container: AppContainer, onOpenEvent: (String) -> Unit) {
    val viewModel: UpcomingViewModel = viewModel { UpcomingViewModel(container) }
    val state by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Upcoming") },
                actions = {
                    IconButton(onClick = viewModel::refresh) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            FilterRow(
                scope = state.filter.scope,
                phenomena = state.filter.phenomena,
                onScopeChange = viewModel::setScope,
                onTogglePhenomenon = viewModel::togglePhenomenon,
            )
            PullToRefreshBox(
                isRefreshing = state.isRefreshing,
                onRefresh = viewModel::refresh,
                modifier = Modifier.weight(1f),
            ) {
                Column(Modifier.fillMaxSize()) {
                    state.auroraBanner?.let { banner ->
                        AuroraNowcastBanner(
                            banner = banner,
                            onClick = { onOpenEvent(banner.occurrenceId) },
                        )
                    }
                    when {
                        state.isLoading -> Box(
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(Modifier.padding(16.dp))
                        }
                        state.items.isEmpty() -> EmptyState(state.filter.scope, Modifier.weight(1f))
                        else -> LazyColumn(
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(state.items, key = { it.occurrence.id }) { item ->
                                UpcomingCard(item, onClick = { onOpenEvent(item.occurrence.id) })
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterRow(
    scope: UpcomingScope,
    phenomena: Set<Phenomenon>,
    onScopeChange: (UpcomingScope) -> Unit,
    onTogglePhenomenon: (Phenomenon) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(selected = scope == UpcomingScope.MATCHED, onClick = { onScopeChange(UpcomingScope.MATCHED) }, label = { Text("Matched") })
        FilterChip(selected = scope == UpcomingScope.ALL, onClick = { onScopeChange(UpcomingScope.ALL) }, label = { Text("All") })
    }
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (phenomenon in Phenomenon.entries) {
            FilterChip(
                selected = phenomenon in phenomena,
                onClick = { onTogglePhenomenon(phenomenon) },
                label = { Text(phenomenonLabel(phenomenon)) },
            )
        }
    }
}

@Composable
private fun EmptyState(scope: UpcomingScope, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            if (scope == UpcomingScope.MATCHED) "Nothing matches your rules yet." else "Nothing in your horizon yet.",
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

@Composable
private fun AuroraNowcastBanner(banner: AuroraBannerUiState, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "Aurora possible NOW at ${banner.locationName}",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                banner.currentKp?.let { "Kp ${it.oneDecimal()} estimated now." } ?: "Live Kp unavailable.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                banner.ovationProbabilityPercent?.let { probability ->
                    "OVATION $probability% overhead probability (${banner.issuedAt.hhmmUtc()} UTC forecast)."
                } ?: "OVATION probability unavailable (${banner.issuedAt.hhmmUtc()} UTC forecast).",
                style = MaterialTheme.typography.bodyMedium,
            )
            banner.darknessStart?.let { darknessStart ->
                Text(
                    "Look north after full darkness (~${darknessStart.hhmmLocal()}).",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun UpcomingCard(item: UpcomingItem, onClick: () -> Unit) {
    val occ = item.occurrence
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(occ.title, style = MaterialTheme.typography.titleMedium)
            Text(countdownText(anchor(occ), Clock.System.now()), style = MaterialTheme.typography.bodySmall)
            Text(locationLine(item), style = MaterialTheme.typography.bodyMedium)
            Text(qualityLabel(item.bestVisres.quality), color = qualityColor(item.bestVisres.quality), style = MaterialTheme.typography.labelLarge)
        }
    }
}

private fun anchor(occ: Occurrence): Instant = occ.peakTime ?: occ.window.start

private fun locationLine(item: UpcomingItem): String {
    val travelKm = item.bestVisres.travelDistanceKm
    if (item.bestVisres.visibleAtLocation || travelKm == null) {
        return "Visible from ${item.bestLocation.name} — ${qualityLabel(item.bestVisres.quality)}"
    }
    val compass = dev.fritze.skyward.core.format.compassOf(item.bestVisres.travelBearingDeg)
    val direction = if (compass.isEmpty()) "" else "$compass "
    return "${travelKm.toInt()} km ${direction}of ${item.bestLocation.name}"
}

private fun qualityLabel(quality: Quality) = when (quality) {
    Quality.NONE -> "Not visible"
    Quality.MARGINAL -> "Marginal"
    Quality.GOOD -> "Good"
    Quality.EXCELLENT -> "Excellent"
}

@Composable
private fun qualityColor(quality: Quality) = when (quality) {
    Quality.EXCELLENT -> MaterialTheme.colorScheme.primary
    Quality.GOOD -> MaterialTheme.colorScheme.tertiary
    Quality.MARGINAL -> MaterialTheme.colorScheme.secondary
    Quality.NONE -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun countdownText(target: Instant, now: Instant): String {
    val delta = target - now
    if (delta < Duration.ZERO) return "Past"
    val days = delta.inWholeDays
    return when {
        days == 0L -> "Today"
        days == 1L -> "Tomorrow"
        days < 7 -> "In $days days"
        days < 60 -> "In ${days / 7} week${if (days / 7 == 1L) "" else "s"}"
        else -> "In ${days / 30} month${if (days / 30 == 1L) "" else "s"}"
    }
}

private fun Double.oneDecimal(): String = ((this * 10).roundToInt() / 10.0).toString()

private fun Instant.hhmmLocal(): String = toLocalDateTime(TimeZone.currentSystemDefault()).hhmm()

private fun Instant.hhmmUtc(): String = toLocalDateTime(TimeZone.UTC).hhmm()

private fun kotlinx.datetime.LocalDateTime.hhmm(): String = "${hour.pad2()}:${minute.pad2()}"

private fun Int.pad2(): String = if (this < 10) "0$this" else toString()
