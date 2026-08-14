package dev.fritze.skyward.ui.locations

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.fritze.skyward.data.AppContainer
import dev.fritze.skyward.ui.common.rememberLocationPermissionRequester

/** §13.1: "map-less: search box + lat/lon + 'use current location'". No geocoding search box -- no place-name lookup source is defined anywhere in the design's data sources (§7), so manual lat/lon plus device location are the two real paths. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocationEditorScreen(container: AppContainer, locationId: String?, onDone: () -> Unit) {
    val viewModel: LocationEditorViewModel = viewModel { LocationEditorViewModel(container, locationId) }

    var name by remember { mutableStateOf("") }
    var lat by remember { mutableStateOf("") }
    var lon by remember { mutableStateOf("") }
    var isPrimary by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(locationId == null) }

    LaunchedEffect(locationId) {
        viewModel.load()?.let {
            name = it.name
            lat = it.point.latDeg.toString()
            lon = it.point.lonDeg.toString()
            isPrimary = it.isPrimary
        }
        loaded = true
    }

    val requestLocation = rememberLocationPermissionRequester { point ->
        if (point != null) {
            lat = point.latDeg.toString()
            lon = point.lonDeg.toString()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (locationId == null) "Add location" else "Edit location") },
                navigationIcon = { IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        if (!loaded) return@Scaffold
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = lat, onValueChange = { lat = it }, label = { Text("Latitude") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = lon, onValueChange = { lon = it }, label = { Text("Longitude") }, modifier = Modifier.fillMaxWidth())
            OutlinedButton(onClick = requestLocation) { Text("Use current location") }
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Checkbox(checked = isPrimary, onCheckedChange = { isPrimary = it })
                Text("Primary location")
            }
            val latValue = lat.toDoubleOrNull()
            val lonValue = lon.toDoubleOrNull()
            Button(
                onClick = { if (latValue != null && lonValue != null && name.isNotBlank()) viewModel.save(name, latValue, lonValue, isPrimary, onDone) },
                enabled = name.isNotBlank() && latValue != null && lonValue != null && latValue in -90.0..90.0 && lonValue in -180.0..180.0,
            ) { Text("Save") }
        }
    }
}
