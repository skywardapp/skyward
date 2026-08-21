package dev.fritze.skyward.desktop.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.fritze.skyward.core.format.CoordinateAxis
import dev.fritze.skyward.core.format.deleteLocationConfirmation
import dev.fritze.skyward.core.format.parseCoordinate
import dev.fritze.skyward.core.model.GeoPoint
import dev.fritze.skyward.core.model.SavedLocation
import dev.fritze.skyward.core.persistence.deleteLocation
import dev.fritze.skyward.core.rules.locationDeletionImpact
import dev.fritze.skyward.desktop.ui.DesktopAppState
import dev.fritze.skyward.desktop.ui.common.SectionCard
import dev.fritze.skyward.desktop.ui.common.formatDegrees
import java.util.UUID
import kotlin.time.Clock

/**
 * Saved locations. Desktop has no location permission flow at all (§13.1's
 * disclosure is a Play requirement for Android's optional coarse-location
 * path) — manual entry is the only path here, which is also the default path
 * on Android.
 */
@Composable
internal fun LocationsSection(state: DesktopAppState) {
    val locations by state.locations.collectAsState()
    val rules by state.visibleRules.collectAsState()
    var draft by remember { mutableStateOf<SavedLocation?>(null) }
    var pendingDelete by remember { mutableStateOf<SavedLocation?>(null) }

    SectionCard("Locations") {
        if (locations.isEmpty()) {
            Text(
                "No saved locations. Every visibility calculation is relative to a place, so add at least one.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        for (location in locations) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(
                        location.name + if (location.isPrimary) " (primary)" else "",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        "${formatDegrees(location.point.latDeg, 3)}, ${formatDegrees(location.point.lonDeg, 3)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { draft = location }) { Text("Edit") }
                TextButton(onClick = { pendingDelete = location }) { Text("Delete") }
            }
        }

        // The delete cancels reminders and rewrites every rule that named this
        // location; neither is visible afterwards, so it asks first and says
        // which rules it is about to change.
        pendingDelete?.let { location ->
            val copy = deleteLocationConfirmation(location.name, locationDeletionImpact(location.id, rules))
            AlertDialog(
                onDismissRequest = { pendingDelete = null },
                title = { Text(copy.title) },
                text = { Text(copy.body) },
                confirmButton = {
                    TextButton(onClick = {
                        pendingDelete = null
                        if (draft?.id == location.id) draft = null
                        state.launch {
                            // Promoting a new primary and repairing rule
                            // references are part of the delete -- core's
                            // `deleteLocation` does both for either frontend.
                            deleteLocation(state.container.locationRepo, state.container.ruleRepo, location.id, Clock.System.now())
                            state.container.replan()
                        }
                    }) { Text("Delete") }
                },
                dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } },
            )
        }

        val editing = draft
        if (editing == null) {
            TextButton(onClick = {
                val now = Clock.System.now()
                draft = SavedLocation(
                    id = UUID.randomUUID().toString(),
                    name = "",
                    point = GeoPoint(0.0, 0.0),
                    isPrimary = locations.isEmpty(),
                    createdAt = now,
                    modifiedAt = now,
                )
            }) { Text("Add location") }
        } else {
            LocationEditor(
                draft = editing,
                onChange = { draft = it },
                onCancel = { draft = null },
                onSave = {
                    state.launch {
                        state.container.locationRepo.upsert(editing.copy(modifiedAt = Clock.System.now()))
                        // A new or moved location changes every visibility result,
                        // so the plan must be recomputed, not just refreshed (§9.7).
                        state.container.replan()
                    }
                    draft = null
                },
            )
        }
    }
}

@Composable
private fun LocationEditor(
    draft: SavedLocation,
    onChange: (SavedLocation) -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit,
) {
    var latText by remember(draft.id) { mutableStateOf(draft.point.latDeg.toString()) }
    var lonText by remember(draft.id) { mutableStateOf(draft.point.lonDeg.toString()) }

    // §5's ranges (including the deliberately exclusive upper bound on
    // longitude) live in `parseCoordinate` so the two frontends can't drift
    // apart on them again -- Android used to accept exactly 180.
    val latEntry = parseCoordinate(latText, CoordinateAxis.LATITUDE)
    val lonEntry = parseCoordinate(lonText, CoordinateAxis.LONGITUDE)

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = draft.name,
            onValueChange = { onChange(draft.copy(name = it)) },
            label = { Text("Name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                value = latText,
                onValueChange = {
                    latText = it
                    parseCoordinate(it, CoordinateAxis.LATITUDE).degrees?.let { value -> onChange(draft.copy(point = draft.point.copy(latDeg = value))) }
                },
                label = { Text("Latitude") },
                isError = latEntry.isError,
                supportingText = latEntry.error?.let { message -> { Text(message) } },
                singleLine = true,
                modifier = Modifier.width(180.dp),
            )
            OutlinedTextField(
                value = lonText,
                onValueChange = {
                    lonText = it
                    parseCoordinate(it, CoordinateAxis.LONGITUDE).degrees?.let { value -> onChange(draft.copy(point = draft.point.copy(lonDeg = value))) }
                },
                label = { Text("Longitude") },
                isError = lonEntry.isError,
                supportingText = lonEntry.error?.let { message -> { Text(message) } },
                singleLine = true,
                modifier = Modifier.width(180.dp),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TextButton(onClick = { onChange(draft.copy(isPrimary = !draft.isPrimary)) }) {
                Text(if (draft.isPrimary) "Primary location ✓" else "Make primary")
            }
            Button(
                onClick = onSave,
                enabled = draft.name.isNotBlank() && latEntry.degrees != null && lonEntry.degrees != null,
            ) { Text("Save") }
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
    }
}
