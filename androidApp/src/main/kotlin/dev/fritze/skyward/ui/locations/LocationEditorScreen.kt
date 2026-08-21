package dev.fritze.skyward.ui.locations

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.fritze.skyward.core.format.CoordinateAxis
import dev.fritze.skyward.core.format.parseCoordinate
import dev.fritze.skyward.data.AppContainer
import dev.fritze.skyward.ui.common.CoordinateField
import dev.fritze.skyward.ui.common.LocationFixOutcome
import dev.fritze.skyward.ui.common.failureMessage
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
    var fixFailure by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(locationId) {
        viewModel.load()?.let {
            name = it.name
            lat = it.point.latDeg.toString()
            lon = it.point.lonDeg.toString()
            isPrimary = it.isPrimary
        }
        loaded = true
    }

    val requestLocation = rememberLocationPermissionRequester { outcome ->
        fixFailure = outcome.failureMessage
        if (outcome is LocationFixOutcome.Fixed) {
            lat = outcome.point.latDeg.toString()
            lon = outcome.point.lonDeg.toString()
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
        // Scrollable: two fields with a hint line each, plus a possible
        // location-fix failure message, is more than a short screen holds once
        // the keyboard is up.
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val latEntry = parseCoordinate(lat, CoordinateAxis.LATITUDE)
            val lonEntry = parseCoordinate(lon, CoordinateAxis.LONGITUDE)
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            CoordinateField(value = lat, entry = latEntry, axis = CoordinateAxis.LATITUDE, onValueChange = { lat = it }, modifier = Modifier.fillMaxWidth())
            CoordinateField(
                value = lon,
                entry = lonEntry,
                axis = CoordinateAxis.LONGITUDE,
                onValueChange = { lon = it },
                modifier = Modifier.fillMaxWidth(),
                imeAction = ImeAction.Done,
            )
            OutlinedButton(onClick = requestLocation) { Text("Use current location") }
            fixFailure?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Checkbox(checked = isPrimary, onCheckedChange = { isPrimary = it })
                Text("Primary location")
            }
            val latValue = latEntry.degrees
            val lonValue = lonEntry.degrees
            Button(
                onClick = { if (latValue != null && lonValue != null && name.isNotBlank()) viewModel.save(name, latValue, lonValue, isPrimary, onDone) },
                enabled = name.isNotBlank() && latValue != null && lonValue != null,
            ) { Text("Save") }
        }
    }
}
