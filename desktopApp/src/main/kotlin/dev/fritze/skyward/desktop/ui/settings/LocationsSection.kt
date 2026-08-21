package dev.fritze.skyward.desktop.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
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
import dev.fritze.skyward.core.format.formatDegrees
import dev.fritze.skyward.core.model.GeoPoint
import dev.fritze.skyward.core.model.SavedLocation
import dev.fritze.skyward.desktop.ui.DesktopAppState
import dev.fritze.skyward.desktop.ui.common.SectionCard
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
    var draft by remember { mutableStateOf<SavedLocation?>(null) }

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
                TextButton(onClick = {
                    state.launch {
                        state.container.locationRepo.delete(location.id)
                        // Deleting the primary would otherwise leave the app
                        // with none, and screens that default to it (the sky
                        // chart, the map's home marker) with nothing to pick.
                        if (location.isPrimary) {
                            state.container.locationRepo.getAll().firstOrNull()?.let {
                                state.container.locationRepo.upsert(it.copy(isPrimary = true, modifiedAt = Clock.System.now()))
                            }
                        }
                        state.container.replan()
                    }
                }) { Text("Delete") }
            }
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

    val lat = latText.toDoubleOrNull()
    val lon = lonText.toDoubleOrNull()
    val latValid = lat != null && lat in -90.0..90.0
    // §5: "lon in [-180, 180)" — the upper bound is deliberately exclusive.
    val lonValid = lon != null && lon >= -180.0 && lon < 180.0

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
                    it.toDoubleOrNull()?.let { value -> onChange(draft.copy(point = draft.point.copy(latDeg = value))) }
                },
                label = { Text("Latitude") },
                isError = latText.isNotEmpty() && !latValid,
                singleLine = true,
                modifier = Modifier.width(180.dp),
            )
            OutlinedTextField(
                value = lonText,
                onValueChange = {
                    lonText = it
                    it.toDoubleOrNull()?.let { value -> onChange(draft.copy(point = draft.point.copy(lonDeg = value))) }
                },
                label = { Text("Longitude") },
                isError = lonText.isNotEmpty() && !lonValid,
                singleLine = true,
                modifier = Modifier.width(180.dp),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TextButton(onClick = { onChange(draft.copy(isPrimary = !draft.isPrimary)) }) {
                Text(if (draft.isPrimary) "Primary location ✓" else "Make primary")
            }
            Button(onClick = onSave, enabled = draft.name.isNotBlank() && latValid && lonValid) { Text("Save") }
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
    }
}
