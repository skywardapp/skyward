package dev.fritze.skyward.desktop.ui.eventdetail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import dev.fritze.skyward.core.format.compassOf
import dev.fritze.skyward.core.model.Certainty
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
import dev.fritze.skyward.desktop.ui.common.formatDateTime
import dev.fritze.skyward.desktop.ui.common.formatDegrees
import dev.fritze.skyward.desktop.ui.common.formatDistanceKm
import dev.fritze.skyward.desktop.ui.common.formatPercent
import dev.fritze.skyward.desktop.ui.common.formatRelative
import dev.fritze.skyward.desktop.ui.common.formatTime
import dev.fritze.skyward.desktop.ui.common.openInBrowser
import dev.fritze.skyward.desktop.ui.common.phenomenonLabel
import dev.fritze.skyward.desktop.ui.theme.qualityColor
import dev.fritze.skyward.desktop.ui.theme.qualityLabel
import kotlin.time.Clock
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
    val perLocation = remember(occurrence, locations, ctx) {
        val model = state.container.visibilityModels[occurrence.phenomenon]
        if (model == null) emptyList() else locations.map { it to model.evaluate(occurrence, it, ctx) }
    }
    val isMuted = rules.any { it.id == muteRuleId(occurrenceId) && it.enabled }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        DetailHeader(state, occurrence, now, onClose)

        HorizontalDivider()

        if (perLocation.isEmpty()) {
            Text("Add a saved location in Settings to see local circumstances.", style = MaterialTheme.typography.bodyMedium)
        } else {
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
    }
}

@Composable
private fun DetailHeader(state: DesktopAppState, occurrence: Occurrence, now: Instant, onClose: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            Text(occurrence.title, style = MaterialTheme.typography.headlineSmall)
            Text(
                "${phenomenonLabel(occurrence.phenomenon)} · ${occurrence.certainty.describe()}",
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
            localDetailLines(visres.localDetails, state).forEach {
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
            Text(
                "Predicted magnitude ${details?.predictedMag?.roundTo(1) ?: payload.peakMag.roundTo(1)} " +
                    "(best ${payload.peakMag.roundTo(1)} around ${formatDateTime(payload.peakMagDate, state.zone)})",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "From JPL orbital elements of ${formatDateTime(payload.elements.epoch, state.zone)}.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Comets often deviate from prediction — treat this as a rough guide, not a guarantee.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = { openInBrowser("https://ssd.jpl.nasa.gov/tools/sbdb_lookup.html#/?sstr=${payload.designation}") }) {
                Text("View on JPL Small-Body Database")
            }
        }
    }
}

private fun localDetailLines(details: LocalDetails?, state: DesktopAppState): List<String> = when (details) {
    is LocalDetails.SolarEclipseLocal -> listOfNotNull(
        "Max ${formatPercent(details.maxObscuration)} obscuration at ${formatTime(details.peak, state.zone)}, sun ${formatDegrees(details.sunAltAtPeakDeg)} up",
        details.localKind?.let { "Locally: ${it.name.lowercase()}" },
    )
    is LocalDetails.LunarEclipseLocal -> listOf(
        "Visible ${formatTime(details.visiblePhaseStart, state.zone)}–${formatTime(details.visiblePhaseEnd, state.zone)}, " +
            "moon ${formatDegrees(details.moonAltAtMidDeg)} up at mid-eclipse",
    )
    is LocalDetails.MeteorLocal -> listOfNotNull(
        details.bestViewingStart?.let { start ->
            "Best ${formatTime(start, state.zone)}–${details.bestViewingEnd?.let { formatTime(it, state.zone) } ?: "dawn"}"
        },
        "Radiant up to ${formatDegrees(details.maxRadiantAltDeg)}, Moon ${formatPercent(details.moonIllumination)}",
    )
    is LocalDetails.AuroraLocal -> listOfNotNull(
        "Geomagnetic latitude ${formatDegrees(details.geomagneticLatDeg, 1)} — needs Kp ${details.kpNeeded.roundTo(1)}",
        details.ovationProbability?.let { "OVATION overhead probability $it %" },
        details.darknessStart?.let { start ->
            "Dark ${formatTime(start, state.zone)}–${details.darknessEnd?.let { formatTime(it, state.zone) } ?: "dawn"}"
        } ?: "No astronomical darkness tonight",
    )
    is LocalDetails.CometLocal -> listOfNotNull(
        "Highest at ${formatDegrees(details.maxAltDeg)}" + (details.maxAltTime?.let { " around ${formatTime(it, state.zone)}" } ?: ""),
    )
    is LocalDetails.GenericLocal -> listOf(details.note)
    null -> emptyList()
}

private fun Certainty.describe() = when (this) {
    Certainty.CERTAIN -> "Ephemeris-derived"
    Certainty.FORECAST -> "Forecast — may change"
}

private fun Double.roundTo(decimals: Int): Double {
    val factor = if (decimals == 1) 10.0 else 100.0
    return kotlin.math.round(this * factor) / factor
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
