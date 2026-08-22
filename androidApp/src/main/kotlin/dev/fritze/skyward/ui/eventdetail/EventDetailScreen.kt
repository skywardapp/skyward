package dev.fritze.skyward.ui.eventdetail

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.fritze.skyward.core.format.COMET_DEVIATION_CAVEAT
import dev.fritze.skyward.core.format.certaintyLabel
import dev.fritze.skyward.core.format.cometElementsLine
import dev.fritze.skyward.core.format.cometMagnitudeLine
import dev.fritze.skyward.core.format.compassOf
import dev.fritze.skyward.core.format.formatDateTime
import dev.fritze.skyward.core.format.formatDistanceKm
import dev.fritze.skyward.core.format.formatRelative
import dev.fritze.skyward.core.format.localDetailLines
import dev.fritze.skyward.core.format.phenomenonLabel
import dev.fritze.skyward.core.format.qualityLabel
import dev.fritze.skyward.core.format.relativeChangeAfter
import dev.fritze.skyward.core.model.CometPayload
import dev.fritze.skyward.core.model.LocalDetails
import dev.fritze.skyward.core.model.Occurrence
import dev.fritze.skyward.core.model.OccurrencePayload
import dev.fritze.skyward.core.model.SavedLocation
import dev.fritze.skyward.core.model.TerrestrialPayload
import dev.fritze.skyward.core.model.VisibilityResult
import dev.fritze.skyward.data.AppContainer
import dev.fritze.skyward.ui.common.qualityColor
import dev.fritze.skyward.ui.rules.LEAD_PRESETS
import dev.fritze.skyward.ui.rules.formatLead
import kotlinx.coroutines.delay
import kotlinx.datetime.TimeZone
import java.net.URI
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventDetailScreen(container: AppContainer, occurrenceId: String, onBack: () -> Unit) {
    val viewModel: EventDetailViewModel = viewModel { EventDetailViewModel(container, occurrenceId) }
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    // §5: the one place an Instant becomes a wall-clock time on this screen.
    val zone = remember { TimeZone.currentSystemDefault() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.occurrence?.title ?: "Event") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        val occurrence = state.occurrence
        if (occurrence == null) {
            NoOccurrenceContent(padding, isLoading = state.isLoading)
            return@Scaffold
        }

        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { DetailHeader(occurrence, zone) }
            if (!state.hasSavedLocations) {
                item {
                    Text("Add a saved location in Settings to see local circumstances.", style = MaterialTheme.typography.bodyMedium)
                }
            } else {
                items(state.perLocation, key = { it.first.id }) { (location, visres) ->
                    LocationCard(location, visres, zone)
                }
            }
            item { PayloadExtras(occurrence.payload, state.perLocation.firstOrNull()?.second, zone, context) }
            item {
                EventDetailActions(
                    state.isMuted,
                    onToggleMute = viewModel::toggleMute,
                    onShare = { shareOccurrence(context, occurrence, state.perLocation, zone) },
                    extraReminderLead = state.extraReminderLead,
                    onSetExtraReminder = viewModel::setExtraReminder,
                    onRemoveExtraReminder = viewModel::removeExtraReminder,
                )
            }
        }
    }
}

/**
 * A detail route outlives the row it points at: an aurora nowcast expires
 * (§7.3), a source is switched off, the horizon window moves on. Saying so is
 * the difference between a dead end and an explanation — the desktop pane has
 * always drawn this distinction, Android showed "Loading…" either way.
 */
@Composable
private fun NoOccurrenceContent(padding: PaddingValues, isLoading: Boolean) {
    Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
        if (isLoading) {
            CircularProgressIndicator()
        } else {
            Text(
                "This event is no longer in the horizon window.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(32.dp),
            )
        }
    }
}

/**
 * §13.3's header: what this is, how certain it is, and — the whole point of
 * opening the screen — when it happens. The countdown is the same
 * `formatRelative` the desktop pane and the timeline use; the date beside it
 * is what someone planning around an eclipse actually needs, and what the
 * Upcoming card's "In 3 weeks" cannot give them (§13.2).
 */
@Composable
private fun DetailHeader(occurrence: Occurrence, zone: TimeZone) {
    val anchor = occurrence.peakTime ?: occurrence.window.start
    val now by tickingNow(anchor)

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "${phenomenonLabel(occurrence.phenomenon)} · ${certaintyLabel(occurrence.certainty)}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text("${formatDateTime(anchor, zone)} — ${formatRelative(now, anchor)}", style = MaterialTheme.typography.bodyLarge)
        Text(
            "Window ${formatDateTime(occurrence.window.start, zone)} → ${formatDateTime(occurrence.window.end, zone)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The countdown above is a function of *now* as much as of the database, and
 * nothing else on this screen re-emits when it changes (the view-model only
 * wakes for repository emissions). Rather than polling, sleep until the exact
 * instant the label can read differently — the same shape as §13.2's
 * `UpcomingTicker`, one label wide.
 */
@Composable
private fun tickingNow(anchor: Instant): State<Instant> = produceState(Clock.System.now(), anchor) {
    while (true) {
        val now = Clock.System.now()
        value = now
        // The floor keeps a boundary that is already upon us (or a clock that
        // stepped backwards) from spinning.
        delay((relativeChangeAfter(now, anchor) - now).coerceAtLeast(1.seconds))
    }
}

/** The comet-compliance block and/or EONET link, depending on which payload type this occurrence carries. */
@Composable
private fun PayloadExtras(payload: OccurrencePayload, primaryVisres: VisibilityResult?, zone: TimeZone, context: Context) {
    if (payload is CometPayload) {
        CometComplianceBlock(payload, primaryVisres, zone)
    }
    // §19 R1/issue #65: `payload.link` is remote-supplied (EONET JSON) and
    // reaches here unvalidated -- a compromised/MITMed response could smuggle
    // an `intent:`/`market:`/`file:` URI into ACTION_VIEW, or an empty/garbage
    // link (the parser's default) could crash the activity. Only offer the
    // button for a link that actually resolves to the fixed EONET API host,
    // and swallow a launch failure rather than crash if no app claims it.
    if (payload is TerrestrialPayload && isSafeEonetLink(payload.link)) {
        OutlinedButton(onClick = {
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(payload.link))) }
        }) {
            Text("Open on EONET")
        }
    }
}

private const val EONET_LINK_HOST = "eonet.gsfc.nasa.gov"

/**
 * True only for an `http(s)` link whose host is exactly [EONET_LINK_HOST] --
 * not a subdomain, suffix, or userinfo trick on top of it. Parsed with
 * `java.net.URI` (a plain JVM class, so this stays testable without
 * Robolectric) rather than by hand: a hand-rolled authority split missed
 * that WHATWG-conformant URL parsers -- what an ACTION_VIEW target such as a
 * browser actually uses -- treat a backslash as a path separator inside an
 * http(s) URL, so `https://evil.com\@eonet.gsfc.nasa.gov/x` would resolve to
 * evil.com there even though an RFC 3986 parser reads one authority
 * component ending in the real host (#65 review). Rejecting any backslash
 * outright closes that gap regardless of which parser eventually handles the
 * link. Failing closed (returning false) on anything that doesn't parse
 * cleanly, or that carries userinfo at all, just means the button doesn't
 * show, which is the safe direction.
 */
internal fun isSafeEonetLink(link: String): Boolean {
    if (link.contains('\\')) return false
    val uri = runCatching { URI(link) }.getOrNull() ?: return false
    val scheme = uri.scheme?.lowercase()
    if (scheme != "http" && scheme != "https") return false
    if (uri.userInfo != null) return false
    return uri.host?.equals(EONET_LINK_HOST, ignoreCase = true) == true
}

@Composable
private fun EventDetailActions(
    isMuted: Boolean,
    onToggleMute: () -> Unit,
    onShare: () -> Unit,
    extraReminderLead: Duration?,
    onSetExtraReminder: (Duration) -> Unit,
    onRemoveExtraReminder: () -> Unit,
) {
    var showPicker by rememberSaveable { mutableStateOf(false) }

    HorizontalDivider()
    Row2 {
        Button(onClick = onToggleMute) { Text(if (isMuted) "Unmute this event" else "Mute this event") }
        TextButton(onClick = onShare) { Text("Share") }
    }
    Row2 {
        TextButton(onClick = { showPicker = true }) {
            Text(if (extraReminderLead == null) "Add one-off extra reminder" else "Extra reminder: ${formatLead(extraReminderLead)}")
        }
        if (extraReminderLead != null) {
            TextButton(onClick = onRemoveExtraReminder) { Text("Remove") }
        }
    }

    if (showPicker) {
        ExtraReminderPickerDialog(
            onPick = { lead -> onSetExtraReminder(lead); showPicker = false },
            onDismiss = { showPicker = false },
        )
    }
}

/**
 * §13.3: "add one-off extra reminder" -- the same lead presets as the rules
 * screen (`LEAD_PRESETS`), plus a custom field whose unit the user picks
 * (minutes/hours/days) rather than assuming hours, since a lead as fine as
 * 30 minutes or as coarse as several weeks is a legitimate choice here too.
 */
@Composable
private fun ExtraReminderPickerDialog(onPick: (Duration) -> Unit, onDismiss: () -> Unit) {
    var customAmount by remember { mutableStateOf("") }
    var customUnit by remember { mutableStateOf(LeadUnit.HOURS) }
    val customLead = customAmount.toPositiveDoubleOrNull()
        ?.let { customUnit.toDuration(it) }
        ?.takeIf { it.isFinite() && it.isPositive() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Remind me before this event") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    for (preset in LEAD_PRESETS) {
                        FilterChip(selected = false, onClick = { onPick(preset) }, label = { Text(formatLead(preset)) })
                    }
                }
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = customAmount,
                        onValueChange = { customAmount = it },
                        label = { Text("Custom") },
                        singleLine = true,
                        modifier = Modifier.width(100.dp),
                    )
                    for (unit in LeadUnit.entries) {
                        FilterChip(selected = customUnit == unit, onClick = { customUnit = unit }, label = { Text(unit.label) })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { customLead?.let(onPick) },
                enabled = customLead != null,
            ) { Text("Set") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** A lead must be a real, positive duration -- a zero or negative one before "PEAK" would fire at or after it. */
private fun String.toPositiveDoubleOrNull(): Double? = toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 }

private enum class LeadUnit(val label: String, val toDuration: (Double) -> Duration) {
    MINUTES("min", { it.minutes }),
    HOURS("hr", { it.hours }),
    DAYS("day", { it.days }),
}

@Composable
private fun Row2(content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), content = content)
}

/**
 * §13.3's times table for one saved location — the same lines the desktop
 * pane draws, from the same `core/format` helpers.
 */
@Composable
private fun LocationCard(location: SavedLocation, visres: VisibilityResult, zone: TimeZone) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(qualityColor(visres.quality)))
                Text(location.name, style = MaterialTheme.typography.titleMedium)
                Text(qualityLabel(visres.quality), style = MaterialTheme.typography.labelLarge, color = qualityColor(visres.quality))
            }
            Text(if (visres.visibleAtLocation) "Visible from here" else "Not visible from here", style = MaterialTheme.typography.bodyMedium)
            val travelKm = visres.travelDistanceKm
            if (travelKm != null) {
                Text(
                    "≈${formatDistanceKm(travelKm)} ${compassOf(visres.travelBearingDeg)} reaches " +
                        (visres.qualityAtNearestPoint?.let { qualityLabel(it).lowercase() } ?: "better conditions"),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            localDetailLines(visres.localDetails, zone).forEach {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/**
 * §7.4.4: the deviation caveat is mandatory, not optional — the same block
 * the desktop pane renders.
 */
@Composable
private fun CometComplianceBlock(payload: CometPayload, visres: VisibilityResult?, zone: TimeZone) {
    val details = visres?.localDetails as? LocalDetails.CometLocal
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Comet forecast", style = MaterialTheme.typography.titleSmall)
            Text(cometMagnitudeLine(payload, details, zone), style = MaterialTheme.typography.bodyMedium)
            Text(
                cometElementsLine(payload, details),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                COMET_DEVIATION_CAVEAT,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val context = LocalContext.current
            TextButton(onClick = {
                val url = "https://ssd.jpl.nasa.gov/tools/sbdb_lookup.html#/?sstr=" + Uri.encode(payload.designation)
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            }) { Text("View on JPL Small-Body Database") }
        }
    }
}

/**
 * §13.3's "share as text": what someone would have to type out otherwise —
 * when it is, and how it looks from where.
 */
private fun shareOccurrence(
    context: Context,
    occurrence: Occurrence,
    perLocation: List<Pair<SavedLocation, VisibilityResult>>,
    zone: TimeZone,
) {
    val anchor = occurrence.peakTime ?: occurrence.window.start
    val lines = perLocation.joinToString("\n") { (location, visres) -> "${location.name}: ${qualityLabel(visres.quality)}" }
    val body = listOf(occurrence.title, formatDateTime(anchor, zone), lines)
        .filter { it.isNotEmpty() }
        .joinToString("\n\n")
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, occurrence.title)
        putExtra(Intent.EXTRA_TEXT, body)
    }
    context.startActivity(Intent.createChooser(intent, occurrence.title))
}
