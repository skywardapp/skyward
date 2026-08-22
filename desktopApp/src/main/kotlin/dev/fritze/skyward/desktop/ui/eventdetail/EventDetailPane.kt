package dev.fritze.skyward.desktop.ui.eventdetail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
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
import dev.fritze.skyward.core.model.CometPayload
import dev.fritze.skyward.core.model.LocalDetails
import dev.fritze.skyward.core.model.Occurrence
import dev.fritze.skyward.core.model.SavedLocation
import dev.fritze.skyward.core.model.SolarEclipsePayload
import dev.fritze.skyward.core.model.TerrestrialPayload
import dev.fritze.skyward.core.model.VisibilityResult
import dev.fritze.skyward.core.rules.Anchor
import dev.fritze.skyward.core.rules.Cond
import dev.fritze.skyward.core.rules.NotifySchedule
import dev.fritze.skyward.core.rules.Rule
import dev.fritze.skyward.desktop.ui.DesktopAppState
import dev.fritze.skyward.desktop.ui.common.openInBrowser
import dev.fritze.skyward.desktop.ui.rules.LEAD_PRESETS
import dev.fritze.skyward.desktop.ui.rules.describeLead
import dev.fritze.skyward.desktop.ui.theme.qualityColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * §14: "Rules/Settings/EventDetail reuse the same core view-models as
 * Android with desktop layouts (two-pane where natural)" — this is the right
 * pane, shared by Overview, Map and Timeline. Same content as Android's
 * `EventDetailScreen`, laid out for a column that is always on screen rather
 * than a pushed route.
 */
@Composable
fun EventDetailPane(state: DesktopAppState, occurrenceId: String, onClose: () -> Unit) {
    val occurrences by state.occurrences.collectAsState()
    val locations by state.locations.collectAsState()
    val rules by state.allRules.collectAsState()
    val now by state.tick.collectAsState()

    val occurrence = occurrences.firstOrNull { it.id == occurrenceId }
    if (occurrence == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("This event is no longer in the horizon window.", style = MaterialTheme.typography.bodyMedium)
        }
        return
    }

    val ctx = state.visibilityContext(now)
    // Off the composition thread: the visibility models run real astronomy
    // (rise/set searches, eclipse circumstances) once per saved location, and
    // this re-runs on every tick. The pane renders the previous answer until
    // the new one lands rather than stalling the frame.
    val perLocation by produceState(emptyList<Pair<SavedLocation, VisibilityResult>>(), occurrence, locations, ctx) {
        val model = state.container.visibilityModels[occurrence.phenomenon]
        value = if (model == null) {
            emptyList()
        } else {
            withContext(Dispatchers.Default) { locations.map { it to model.evaluate(occurrence, it, ctx) } }
        }
    }
    val isMuted = rules.any { it.id == muteRuleId(occurrenceId) && it.enabled }
    val extraReminderLead = rules.firstOrNull { it.id == extraReminderRuleId(occurrenceId) && it.enabled }?.schedule?.leads?.firstOrNull()

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        DetailHeader(state, occurrence, now, onClose)

        HorizontalDivider()

        if (locations.isEmpty()) {
            Text("Add a saved location in Settings to see local circumstances.", style = MaterialTheme.typography.bodyMedium)
        } else {
            // While the first evaluation is in flight `perLocation` is empty;
            // that is a blank moment, not the "no locations" state above.
            for ((location, visres) in perLocation) {
                LocationVisibilityCard(state, location, visres)
            }
        }

        PayloadExtras(state, occurrence, perLocation.firstOrNull()?.second)

        HorizontalDivider()
        // §13.3's mute, verbatim: a hidden rule that can never fire on its own,
        // whose only job is to suppress this one occurrence.
        OutlinedButton(onClick = { state.launch { toggleMute(state, occurrence, isMuted) } }) {
            Text(if (isMuted) "Unmute this event" else "Mute this event")
        }

        ExtraReminderRow(extraReminderLead) { lead ->
            state.launch {
                if (lead == null) {
                    state.container.ruleRepo.delete(extraReminderRuleId(occurrenceId))
                } else {
                    setExtraReminder(state, occurrence, lead)
                }
                state.container.replan()
            }
        }
    }
}

/**
 * §13.3's "add one-off extra reminder": the same `LEAD_PRESETS` as
 * `RulesScreen`'s `LeadChips`, plus a custom field whose unit the user picks
 * (minutes/hours/days) instead of assuming hours.
 */
@Composable
private fun ExtraReminderRow(currentLead: Duration?, onPick: (Duration?) -> Unit) {
    var customAmount by remember { mutableStateOf("") }
    var customUnit by remember { mutableStateOf(LeadUnit.HOURS) }
    val customLead = customAmount.toPositiveDoubleOrNull()?.let { customUnit.toDuration(it) }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            if (currentLead == null) "Add one-off extra reminder" else "Extra reminder: ${describeLead(currentLead)} before",
            style = MaterialTheme.typography.labelMedium,
        )
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (preset in LEAD_PRESETS) {
                FilterChip(selected = currentLead == preset, onClick = { onPick(preset) }, label = { Text(describeLead(preset)) })
            }
            OutlinedTextField(
                value = customAmount,
                onValueChange = { customAmount = it },
                label = { Text("Custom") },
                singleLine = true,
                modifier = Modifier.width(90.dp),
            )
            for (unit in LeadUnit.entries) {
                FilterChip(selected = customUnit == unit, onClick = { customUnit = unit }, label = { Text(unit.label) })
            }
            TextButton(
                onClick = { customLead?.let(onPick) },
                enabled = customLead != null,
            ) { Text("Set") }
            if (currentLead != null) {
                TextButton(onClick = { onPick(null) }) { Text("Remove") }
            }
        }
    }
}

/** A lead must be a real, positive duration -- a zero or negative one before "PEAK" would fire at or after it. */
private fun String.toPositiveDoubleOrNull(): Double? = toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 }

private enum class LeadUnit(val label: String, val toDuration: (Double) -> Duration) {
    MINUTES("min", { it.minutes }),
    HOURS("hr", { it.hours }),
    DAYS("day", { it.days }),
}

@Composable
private fun DetailHeader(state: DesktopAppState, occurrence: Occurrence, now: Instant, onClose: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            Text(occurrence.title, style = MaterialTheme.typography.headlineSmall)
            Text(
                "${phenomenonLabel(occurrence.phenomenon)} · ${certaintyLabel(occurrence.certainty)}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onClose) { Text("Close") }
    }

    val anchor = occurrence.peakTime ?: occurrence.window.start
    Text("${formatDateTime(anchor, state.zone)} — ${formatRelative(now, anchor)}", style = MaterialTheme.typography.bodyLarge)
    Text(
        "Window ${formatDateTime(occurrence.window.start, state.zone)} → ${formatDateTime(occurrence.window.end, state.zone)}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** The phenomenon-specific tail of the pane: the §7.4.4 comet caveat, the EONET link, the map pointer. */
@Composable
private fun PayloadExtras(state: DesktopAppState, occurrence: Occurrence, primaryVisres: VisibilityResult?) {
    when (val payload = occurrence.payload) {
        is CometPayload -> CometHonestyCard(payload, primaryVisres, state)
        is TerrestrialPayload -> OutlinedButton(onClick = { openInBrowser(payload.link) }) { Text("Open on NASA EONET") }
        is SolarEclipsePayload -> if (payload.centralPath.isNotEmpty()) {
            Text(
                "Central path sampled at ${payload.centralPath.size} points — see it on the Map tab.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        else -> Unit
    }
}

@Composable
private fun LocationVisibilityCard(state: DesktopAppState, location: SavedLocation, visres: VisibilityResult) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(qualityColor(visres.quality)))
                Text(location.name, style = MaterialTheme.typography.titleSmall)
                Text(
                    qualityLabel(visres.quality),
                    style = MaterialTheme.typography.labelMedium,
                    color = qualityColor(visres.quality),
                )
            }
            Text(
                if (visres.visibleAtLocation) "Visible from here" else "Not visible from here",
                style = MaterialTheme.typography.bodyMedium,
            )
            val travelKm = visres.travelDistanceKm
            if (travelKm != null) {
                Text(
                    "≈${formatDistanceKm(travelKm)} ${compassOf(visres.travelBearingDeg)} reaches " +
                        (visres.qualityAtNearestPoint?.let { qualityLabel(it).lowercase() } ?: "better conditions"),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            localDetailLines(visres.localDetails, state.zone).forEach {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** §7.4.4: the deviation caveat is mandatory, not optional — the same block Android renders. */
@Composable
private fun CometHonestyCard(payload: CometPayload, visres: VisibilityResult?, state: DesktopAppState) {
    val details = visres?.localDetails as? LocalDetails.CometLocal
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Comet forecast", style = MaterialTheme.typography.titleSmall)
            Text(cometMagnitudeLine(payload, details, state.zone), style = MaterialTheme.typography.bodyMedium)
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
            // Designations carry spaces and slashes ("C/2023 A3", "73P/Schwassmann-Wachmann").
            val sstr = URLEncoder.encode(payload.designation, StandardCharsets.UTF_8)
            TextButton(onClick = { openInBrowser("https://ssd.jpl.nasa.gov/tools/sbdb_lookup.html#/?sstr=$sstr") }) {
                Text("View on JPL Small-Body Database")
            }
        }
    }
}

internal fun muteRuleId(occurrenceId: String) = "mute:$occurrenceId"

private suspend fun toggleMute(state: DesktopAppState, occurrence: Occurrence, isMuted: Boolean) {
    val ruleId = muteRuleId(occurrence.id)
    if (isMuted) {
        state.container.ruleRepo.delete(ruleId)
    } else {
        val now = Clock.System.now()
        state.container.ruleRepo.upsert(
            Rule(
                id = ruleId,
                name = "Muted: ${occurrence.title}",
                enabled = true,
                phenomena = setOf(occurrence.phenomenon),
                locationIds = null,
                condition = Cond.OccurrenceIdIs(occurrence.id),
                schedule = NotifySchedule(emptyList(), Anchor.PEAK, notifyOnFirstSeen = false, quietHours = null),
                hidden = true,
                createdAt = now,
                modifiedAt = now,
            ),
        )
    }
    state.container.replan()
}

internal fun extraReminderRuleId(occurrenceId: String) = "extra:$occurrenceId"

/**
 * §13.3's "add one-off extra reminder" --
 * hidden(condition=OccurrenceIdIs(id), leads=[lead]).
 */
private suspend fun setExtraReminder(state: DesktopAppState, occurrence: Occurrence, lead: Duration) {
    val now = Clock.System.now()
    state.container.ruleRepo.upsert(
        Rule(
            id = extraReminderRuleId(occurrence.id),
            name = "Extra reminder: ${occurrence.title}",
            enabled = true,
            phenomena = setOf(occurrence.phenomenon),
            locationIds = null,
            condition = Cond.OccurrenceIdIs(occurrence.id),
            schedule = NotifySchedule(listOf(lead), Anchor.PEAK, notifyOnFirstSeen = false, quietHours = null),
            hidden = true,
            createdAt = now,
            modifiedAt = now,
        ),
    )
}
