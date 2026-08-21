package dev.fritze.skyward.ui.locations

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.fritze.skyward.core.format.deleteLocationConfirmation
import dev.fritze.skyward.core.model.SavedLocation
import dev.fritze.skyward.core.rules.Rule
import dev.fritze.skyward.core.rules.locationDeletionImpact
import dev.fritze.skyward.data.AppContainer

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocationsScreen(container: AppContainer, onBack: () -> Unit, onAdd: () -> Unit, onEdit: (String) -> Unit) {
    val viewModel: LocationsViewModel = viewModel { LocationsViewModel(container) }
    val locations by viewModel.locations.collectAsState()
    val rules by viewModel.rules.collectAsState()
    var pendingDelete by remember { mutableStateOf<SavedLocation?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Locations") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
        floatingActionButton = { FloatingActionButton(onClick = onAdd) { Icon(Icons.Filled.Add, contentDescription = "Add location") } },
    ) { padding ->
        if (locations.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(padding).padding(32.dp)) {
                Text("No locations yet. Add one to see what's visible from where you are.")
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(locations, key = { it.id }) { location ->
                    LocationRow(location, onClick = { onEdit(location.id) }, onDelete = { pendingDelete = location })
                }
            }
        }
    }

    // Deleting a location cancels its reminders and rewrites every rule that
    // named it, so it asks first -- rule deletion already did, and the two
    // are equally destructive.
    pendingDelete?.let { location ->
        DeleteLocationDialog(
            location = location,
            rules = rules,
            onDismiss = { pendingDelete = null },
            onConfirm = {
                pendingDelete = null
                viewModel.delete(location.id)
            },
        )
    }
}

@Composable
private fun DeleteLocationDialog(
    location: SavedLocation,
    rules: List<Rule>,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val copy = deleteLocationConfirmation(location.name, locationDeletionImpact(location.id, rules))
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(copy.title) },
        text = { Text(copy.body) },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Delete") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun LocationRow(location: SavedLocation, onClick: () -> Unit, onDelete: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text(location.name + if (location.isPrimary) " (primary)" else "", style = MaterialTheme.typography.titleMedium)
                Text("${location.point.latDeg}, ${location.point.lonDeg}", style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "Delete") }
        }
    }
}
