package dev.fritze.skyward.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Column

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onLocations: () -> Unit, onNotifications: () -> Unit, onSync: () -> Unit, onAbout: () -> Unit) {
    Scaffold(topBar = { TopAppBar(title = { Text("Settings") }) }) { padding ->
        Column(Modifier.fillMaxWidth().padding(padding)) {
            SettingsRow("Locations", "Where Skyward checks visibility from", onLocations)
            HorizontalDivider()
            SettingsRow("Notifications", "Quiet hours, exact-alarm status", onNotifications)
            HorizontalDivider()
            SettingsRow("Sync", "Export / import your settings", onSync)
            HorizontalDivider()
            SettingsRow("About", "Version, licenses, privacy policy", onAbout)
        }
    }
}

@Composable
private fun SettingsRow(title: String, subtitle: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(subtitle, style = MaterialTheme.typography.bodySmall)
    }
}
