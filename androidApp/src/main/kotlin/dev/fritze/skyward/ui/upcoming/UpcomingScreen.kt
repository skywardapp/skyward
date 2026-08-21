package dev.fritze.skyward.ui.upcoming

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.fritze.skyward.core.format.compassOf
import dev.fritze.skyward.core.format.phenomenonLabel
import dev.fritze.skyward.core.format.qualityLabel
import dev.fritze.skyward.core.model.Phenomenon
import dev.fritze.skyward.core.planner.UpcomingItem
import dev.fritze.skyward.core.planner.UpcomingScope
import dev.fritze.skyward.data.AppContainer
import dev.fritze.skyward.ui.common.openAppNotificationSettings
import dev.fritze.skyward.ui.common.qualityColor
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.math.roundToInt
import kotlin.time.Instant

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpcomingScreen(container: AppContainer, onOpenEvent: (String) -> Unit, onOpenLocations: () -> Unit) {
    val context = LocalContext.current
    val viewModel: UpcomingViewModel = viewModel { UpcomingViewModel(container) }
    val state by viewModel.uiState.collectAsState()
    val refreshMessage by viewModel.refreshMessage.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val versionCode = remember(context) { appVersionCode(context) }
    var showExactAlarmCard by remember { mutableStateOf(false) }
    var notificationsBlocked by remember { mutableStateOf(false) }

    fun refreshPermissionCards() {
        scope.launch {
            notificationsBlocked = !container.notificationGate.canPost()
            val dismissedVersion = container.settingsRepo.getExactAlarmCardDismissedVersion()
            showExactAlarmCard = !container.alarmScheduler.canScheduleExact() && dismissedVersion != versionCode
        }
    }

    // Both permissions are changed in a *different* Activity (the system
    // settings screens the cards link to), so resume is the only moment we
    // can learn they moved.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        refreshPermissionCards()
    }

    // A refresh that could not reach a source has nowhere else to say so: the
    // list looks the same either way, and `runDue` has already swallowed the
    // failure into diagnostics (#71). The snackbar is dismissed by showing it
    // — a stale "couldn't reach NOAA" would outlive the outage it describes.
    LaunchedEffect(refreshMessage) {
        val message = refreshMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message, withDismissAction = true)
        viewModel.clearRefreshMessage()
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Upcoming") })
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // One problem at a time, the fatal one first: while nothing can be
            // delivered at all, offering to improve delivery *precision* is
            // noise. The exact-alarm card comes back once notifications do.
            if (notificationsBlocked) {
                NotificationsBlockedCard(
                    onOpenSettings = { openAppNotificationSettings(context) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )
            } else if (showExactAlarmCard) {
                Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Exact alarms are off", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Reminders still work, but they can arrive tens of minutes late. " +
                                "Enable exact alarms for precise delivery.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                context.startActivity(
                                    Intent(
                                        Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                                        Uri.parse("package:${context.packageName}"),
                                    ),
                                )
                            }) { Text("Enable exact alarms") }
                            TextButton(onClick = {
                                scope.launch {
                                    container.settingsRepo.setExactAlarmCardDismissedVersion(versionCode)
                                    showExactAlarmCard = false
                                }
                            }) { Text("Dismiss") }
                        }
                    }
                }
            }
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
                            liveKpFailed = state.liveKpFailed,
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
                        state.items.isEmpty() -> EmptyState(
                            scope = state.filter.scope,
                            hasLocations = state.hasLocations,
                            onOpenLocations = onOpenLocations,
                            modifier = Modifier.weight(1f),
                        )
                        else -> LazyColumn(
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(state.items, key = { it.occurrence.id }) { item ->
                                UpcomingCard(item, state.now, onClick = { onOpenEvent(item.occurrence.id) })
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun appVersionCode(context: Context): Long = runCatching {
    val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        packageInfo.longVersionCode
    } else {
        @Suppress("DEPRECATION")
        packageInfo.versionCode.toLong()
    }
}.getOrDefault(0L)

/**
 * §10.1's honesty contract, at its most literal: with notifications blocked
 * every reminder the app plans is dropped, so the app has to say so where
 * the user actually looks. Unlike the exact-alarm card this one has no
 * "Dismiss" — dismissing it would restore exactly the silent failure the
 * card exists to end. It disappears the moment notifications are re-enabled.
 */
@Composable
private fun NotificationsBlockedCard(onOpenSettings: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Notifications are blocked", style = MaterialTheme.typography.titleSmall)
            Text(
                "Skyward can't reach you. Every reminder is dropped and recorded as missed, no " +
                    "matter how your rules and locations are set up.",
                style = MaterialTheme.typography.bodySmall,
            )
            Button(onClick = onOpenSettings) { Text("Turn notifications on") }
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

/**
 * An empty list has more than one cause, and they need different answers
 * (#71). "No saved location" is the one the user can act on and the one
 * onboarding can leave behind — skipping the location step is an offered
 * path, and Locations then sits two levels down under Settings, which is
 * nowhere near where someone looking at an empty home screen would think to
 * go. Desktop's Overview has drawn this distinction since M6; this is the
 * Android half.
 */
@Composable
private fun EmptyState(
    scope: UpcomingScope,
    hasLocations: Boolean,
    onOpenLocations: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (!hasLocations) {
            Text("No saved locations yet.", style = MaterialTheme.typography.titleMedium)
            Text(
                "Visibility is always computed for a place, so Skyward needs one before it can " +
                    "tell you what is worth going outside for.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
            Button(onClick = onOpenLocations, modifier = Modifier.padding(top = 12.dp)) {
                Text("Add a location")
            }
        } else {
            Text(
                if (scope == UpcomingScope.MATCHED) "Nothing matches your rules yet." else "Nothing in your horizon yet.",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                if (scope == UpcomingScope.MATCHED) {
                    "Switch to \"All\" to see everything in the horizon window."
                } else {
                    "Pull down to refresh — sources are polled on their own schedule."
                },
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun AuroraNowcastBanner(banner: AuroraBannerUiState, liveKpFailed: Boolean, onClick: () -> Unit) {
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
                banner.currentKp?.let { "Kp ${it.oneDecimal()} estimated now." }
                    // "Unavailable" with no reason reads as a bug in Skyward
                    // rather than as an unreachable NOAA (#71).
                    ?: if (liveKpFailed) "Live Kp unavailable — couldn't reach NOAA SWPC." else "Live Kp unavailable.",
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

/**
 * [now] comes from the state rather than the clock so that the countdown line
 * is part of what recomposition compares — UpcomingTicker.kt re-emits the
 * state when it is due to change.
 */
@Composable
private fun UpcomingCard(item: UpcomingItem, now: Instant, onClick: () -> Unit) {
    val occ = item.occurrence
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(occ.title, style = MaterialTheme.typography.titleMedium)
            Text(countdownText(countdownAnchor(occ), now), style = MaterialTheme.typography.bodySmall)
            Text(locationLine(item), style = MaterialTheme.typography.bodyMedium)
            Text(qualityLabel(item.bestVisres.quality), color = qualityColor(item.bestVisres.quality), style = MaterialTheme.typography.labelLarge)
        }
    }
}

private fun locationLine(item: UpcomingItem): String {
    val travelKm = item.bestVisres.travelDistanceKm
    if (item.bestVisres.visibleAtLocation || travelKm == null) {
        return "Visible from ${item.bestLocation.name} — ${qualityLabel(item.bestVisres.quality)}"
    }
    val compass = compassOf(item.bestVisres.travelBearingDeg)
    val direction = if (compass.isEmpty()) "" else "$compass "
    return "${travelKm.toInt()} km ${direction}of ${item.bestLocation.name}"
}

private fun Double.oneDecimal(): String = ((this * 10).roundToInt() / 10.0).toString()

private fun Instant.hhmmLocal(): String = toLocalDateTime(TimeZone.currentSystemDefault()).hhmm()

private fun Instant.hhmmUtc(): String = toLocalDateTime(TimeZone.UTC).hhmm()

private fun kotlinx.datetime.LocalDateTime.hhmm(): String = "${hour.pad2()}:${minute.pad2()}"

private fun Int.pad2(): String = if (this < 10) "0$this" else toString()
