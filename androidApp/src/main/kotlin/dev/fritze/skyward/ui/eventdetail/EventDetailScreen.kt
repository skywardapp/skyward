package dev.fritze.skyward.ui.eventdetail

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.fritze.skyward.core.format.compassOf
import dev.fritze.skyward.core.model.CometPayload
import dev.fritze.skyward.core.model.LocalDetails
import dev.fritze.skyward.core.model.SavedLocation
import dev.fritze.skyward.core.model.TerrestrialPayload
import dev.fritze.skyward.core.model.VisibilityResult
import dev.fritze.skyward.data.AppContainer
import dev.fritze.skyward.ui.rules.formatLead
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventDetailScreen(container: AppContainer, occurrenceId: String, onBack: () -> Unit) {
    val viewModel: EventDetailViewModel = viewModel { EventDetailViewModel(container, occurrenceId) }
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.occurrence?.title.orEmpty()) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        val occurrence = state.occurrence
        if (occurrence == null) {
            LoadingContent(padding)
            return@Scaffold
        }

        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            items(state.perLocation, key = { it.first.id }) { (location, visres) ->
                LocationCard(location, visres)
            }
            item { PayloadExtras(occurrence.payload, state.perLocation.firstOrNull()?.second, context) }
            item {
                EventDetailActions(
                    state.isMuted,
                    onToggleMute = viewModel::toggleMute,
                    onShare = { shareOccurrence(context, occurrence.title, state.perLocation) },
                    extraReminderLead = state.extraReminderLead,
                    onSetExtraReminder = viewModel::setExtraReminder,
                    onRemoveExtraReminder = viewModel::removeExtraReminder,
                )
            }
        }
    }
}

@Composable
private fun LoadingContent(padding: androidx.compose.foundation.layout.PaddingValues) {
    Column(Modifier.fillMaxSize().padding(padding)) { Text("Loading…", Modifier.padding(16.dp)) }
}

/** The comet-compliance block and/or EONET link, depending on which payload type this occurrence carries. */
@Composable
private fun PayloadExtras(payload: dev.fritze.skyward.core.model.OccurrencePayload, primaryVisres: VisibilityResult?, context: android.content.Context) {
    if (payload is CometPayload) {
        CometComplianceBlock(payload, primaryVisres)
    }
    if (payload is TerrestrialPayload) {
        OutlinedButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(payload.link))) }) {
            Text("Open on EONET")
        }
    }
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
 * §13.3: "add one-off extra reminder" -- a small preset set plus a custom
 * hours field is enough.
 */
@Composable
private fun ExtraReminderPickerDialog(onPick: (Duration) -> Unit, onDismiss: () -> Unit) {
    val presets = listOf(1.hours, 6.hours, 1.days, 7.days)
    var customHours by remember { mutableStateOf("") }
    val customLeadHours = customHours.toPositiveHoursOrNull()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Remind me before this event") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (preset in presets) {
                        FilterChip(selected = false, onClick = { onPick(preset) }, label = { Text(formatLead(preset)) })
                    }
                }
                OutlinedTextField(
                    value = customHours,
                    onValueChange = { customHours = it },
                    label = { Text("Custom (hours before)") },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { customLeadHours?.let { onPick(it.hours) } },
                enabled = customLeadHours != null,
            ) { Text("Set") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** A lead must be a real, positive duration -- a zero or negative one before "PEAK" would fire at or after it. */
private fun String.toPositiveHoursOrNull(): Double? = toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 }

@Composable
private fun Row2(content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    androidx.compose.foundation.layout.Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), content = content)
}

@Composable
private fun LocationCard(location: SavedLocation, visres: VisibilityResult) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(location.name, style = MaterialTheme.typography.titleMedium)
            Text("Quality: ${visres.quality}", style = MaterialTheme.typography.bodyMedium)
            Text(if (visres.visibleAtLocation) "Visible from here" else "Not visible from here", style = MaterialTheme.typography.bodyMedium)
            val travelKm = visres.travelDistanceKm
            if (travelKm != null) {
                Text(
                    "Travel guidance: ${travelKm.toInt()} km ${compassOf(visres.travelBearingDeg)} (reaches ${visres.qualityAtNearestPoint})",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            localDetailsSummary(visres.localDetails)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

private fun localDetailsSummary(details: LocalDetails?): String? = when (details) {
    is LocalDetails.SolarEclipseLocal -> "Max obscuration ${(details.maxObscuration * 100).toInt()}%, sun altitude ${details.sunAltAtPeakDeg.toInt()}°"
    is LocalDetails.LunarEclipseLocal -> "Moon altitude at mid-eclipse ${details.moonAltAtMidDeg.toInt()}°"
    is LocalDetails.MeteorLocal -> "Radiant up to ${details.maxRadiantAltDeg.toInt()}°, Moon illumination ${(details.moonIllumination * 100).toInt()}%"
    is LocalDetails.AuroraLocal -> "Geomagnetic latitude ${details.geomagneticLatDeg.toInt()}°, Kp needed ${details.kpNeeded}"
    is LocalDetails.CometLocal -> null // shown in the compliance block instead
    is LocalDetails.GenericLocal -> details.note
    null -> null
}

@Composable
private fun CometComplianceBlock(payload: CometPayload, visres: VisibilityResult?) {
    val details = visres?.localDetails as? LocalDetails.CometLocal
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Comet forecast (§7.4.4)", style = MaterialTheme.typography.titleSmall)
            Text("Predicted magnitude: ${details?.predictedMag ?: payload.peakMag}", style = MaterialTheme.typography.bodyMedium)
            Text("From JPL elements as of ${details?.elementEpoch ?: payload.elements.epoch}", style = MaterialTheme.typography.bodySmall)
            if (details != null) {
                Text("Highest at ${details.maxAltDeg.toInt()}°" + (details.maxAltTime?.let { " around $it" } ?: ""), style = MaterialTheme.typography.bodyMedium)
            }
            Text(
                "Comets frequently deviate from prediction — treat this as a rough guide, not a guarantee.",
                style = MaterialTheme.typography.bodySmall,
            )
            val context = LocalContext.current
            TextButton(onClick = {
                val url = "https://ssd.jpl.nasa.gov/tools/sbdb_lookup.html#/?sstr=" + Uri.encode(payload.designation)
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            }) { Text("View on JPL Small-Body Database") }
        }
    }
}

private fun shareOccurrence(context: android.content.Context, title: String, perLocation: List<Pair<SavedLocation, VisibilityResult>>) {
    val lines = perLocation.joinToString("\n") { (loc, visres) -> "${loc.name}: ${visres.quality}" }
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, title)
        putExtra(Intent.EXTRA_TEXT, "$title\n\n$lines")
    }
    context.startActivity(Intent.createChooser(intent, title))
}
