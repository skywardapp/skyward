package dev.fritze.skyward.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.fritze.skyward.core.persistence.ThemeChoice
import dev.fritze.skyward.data.AppContainer
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    container: AppContainer,
    onLocations: () -> Unit,
    onNotifications: () -> Unit,
    onSources: () -> Unit,
    onSync: () -> Unit,
    onAbout: () -> Unit,
) {
    Scaffold(topBar = { TopAppBar(title = { Text("Settings") }) }) { padding ->
        Column(Modifier.fillMaxWidth().padding(padding).verticalScroll(rememberScrollState())) {
            SettingsRow("Locations", "Where Skyward checks visibility from", onLocations)
            HorizontalDivider()
            SettingsRow("Notifications", "Quiet hours, exact-alarm status", onNotifications)
            HorizontalDivider()
            SettingsRow("Sources", "Enable/disable data sources, diagnostics", onSources)
            HorizontalDivider()
            SettingsRow("Sync", "Export / import your settings", onSync)
            HorizontalDivider()
            SettingsRow("About", "Version, licenses, privacy policy", onAbout)
            HorizontalDivider()
            ThemeRow(container)
        }
    }
}

/**
 * §11's `theme`, the override on top of §13's follow-the-system default. It is
 * inline rather than behind another destination because it is a single
 * three-way choice, and because the point of changing it is to see the result
 * -- which happens on this screen, immediately.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThemeRow(container: AppContainer) {
    val scope = rememberCoroutineScope()
    val theme by container.settingsRepo.observeTheme().collectAsState(initial = ThemeChoice.SYSTEM)

    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Text("Theme", style = MaterialTheme.typography.titleMedium)
        Text("Follows your system setting unless you pick one", style = MaterialTheme.typography.bodySmall)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            ThemeChoice.entries.forEachIndexed { index, choice ->
                SegmentedButton(
                    selected = theme == choice,
                    onClick = { scope.launch { container.settingsRepo.setTheme(choice) } },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = ThemeChoice.entries.size),
                ) { Text(themeLabel(choice)) }
            }
        }
    }
}

private fun themeLabel(choice: ThemeChoice): String = when (choice) {
    ThemeChoice.SYSTEM -> "System"
    ThemeChoice.DARK -> "Dark"
    ThemeChoice.LIGHT -> "Light"
}

@Composable
private fun SettingsRow(title: String, subtitle: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(subtitle, style = MaterialTheme.typography.bodySmall)
    }
}
