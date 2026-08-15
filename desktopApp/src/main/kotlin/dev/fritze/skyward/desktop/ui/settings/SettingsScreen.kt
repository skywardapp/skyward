package dev.fritze.skyward.desktop.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
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
import androidx.compose.ui.unit.dp
import dev.fritze.skyward.desktop.autostart.AutostartResult
import dev.fritze.skyward.desktop.data.DesktopContainer
import dev.fritze.skyward.desktop.notify.DesktopNotification
import dev.fritze.skyward.desktop.ui.DesktopAppState
import dev.fritze.skyward.desktop.ui.common.Dropdown
import dev.fritze.skyward.desktop.ui.common.LabeledRow
import dev.fritze.skyward.desktop.ui.common.NumberField
import dev.fritze.skyward.desktop.ui.common.SectionCard
import dev.fritze.skyward.desktop.ui.theme.ThemeChoice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * §14: Settings, reusing the same `:core` repositories as Android's Settings
 * tree (§13.1) — locations, sources + diagnostics, sync, and the two
 * desktop-only behaviours from §10.3 (background mode, autostart).
 */
@Composable
fun SettingsScreen(state: DesktopAppState) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall)
        LocationsSection(state)
        DesktopIntegrationSection(state)
        HorizonAndAppearanceSection(state)
        SourcesSection(state)
        SyncSection(state)
        AboutSection()
    }
}

/** §10.3: background mode (close hides to tray) and autostart, plus a way to prove notifications work at all. */
@Composable
private fun DesktopIntegrationSection(state: DesktopAppState) {
    val settings by state.settings.collectAsState()

    SectionCard("Desktop integration") {
        BackgroundModeRow(state, enabled = settings[DesktopContainer.KEY_BACKGROUND_MODE] == "true")
        AutostartRow(state, persistedSetting = settings[DesktopContainer.KEY_AUTOSTART] == "true")
        TestNotificationRow(state)
    }
}

@Composable
private fun BackgroundModeRow(state: DesktopAppState, enabled: Boolean) {
    SettingSwitchRow(
        checked = enabled,
        onCheckedChange = { on ->
            state.launch { state.container.settingsRepo.set(DesktopContainer.KEY_BACKGROUND_MODE, on.toString()) }
        },
        title = "Keep running in the background",
        subtitle = "Closing the window hides Skyward to the tray instead of exiting, so reminders keep arriving.",
    )
}

@Composable
private fun AutostartRow(state: DesktopAppState, persistedSetting: Boolean) {
    var note by remember { mutableStateOf<String?>(null) }
    var writes by remember { mutableStateOf(0) }
    // The XDG backend can read the real state off disk; the portal cannot, so
    // there the persisted setting is the only record of what we asked for.
    // Read off the UI thread — it touches the filesystem — and re-read after
    // every write, so the switch shows what is actually on disk rather than
    // what we asked for.
    val enabled by produceState(persistedSetting, persistedSetting, writes) {
        value = withContext(Dispatchers.IO) { runCatching { state.autostart.isEnabled() }.getOrNull() } ?: persistedSetting
    }

    SettingSwitchRow(
        checked = enabled,
        onCheckedChange = { on ->
            state.launch {
                val result = state.autostart.setEnabled(on)
                note = when (result) {
                    is AutostartResult.Applied -> null
                    is AutostartResult.Requested -> result.note
                    is AutostartResult.Failed -> "Could not change autostart: ${result.message}"
                }
                if (result !is AutostartResult.Failed) {
                    state.container.settingsRepo.set(DesktopContainer.KEY_AUTOSTART, on.toString())
                }
                writes++
            }
        },
        title = "Start Skyward at login",
        subtitle = if (state.paths.isFlatpak) {
            "Requested through the desktop's background portal."
        } else {
            "Writes ~/.config/autostart/skyward.desktop."
        },
    )
    note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
}

@Composable
private fun TestNotificationRow(state: DesktopAppState) {
    var note by remember { mutableStateOf<String?>(null) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        TextButton(onClick = {
            // Off the UI thread: both backends block — one on a DBus call, the
            // other on spawning `notify-send` — and a stalled notification
            // daemon would otherwise freeze the window.
            state.launch {
                val delivered = withContext(Dispatchers.IO) {
                    state.notifier.post(
                        DesktopNotification("Skyward test", "Notifications are working.", occurrenceId = null),
                        onActivated = {},
                    )
                }
                note = if (delivered) {
                    "Sent — if nothing appeared, check your desktop's notification settings."
                } else {
                    "No notification backend accepted it. Reminders will still be listed in the app."
                }
            }
        }) { Text("Send a test notification") }
        note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun SettingSwitchRow(checked: Boolean, onCheckedChange: (Boolean) -> Unit, title: String, subtitle: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Switch(checked = checked, onCheckedChange = onCheckedChange)
        Column {
            Text(title)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun HorizonAndAppearanceSection(state: DesktopAppState) {
    val settings by state.settings.collectAsState()
    val horizonYears = settings["horizon_years"]?.toIntOrNull() ?: 3
    val theme = ThemeChoice.parse(settings["theme"])

    SectionCard("Horizon & appearance") {
        LabeledRow("Plan ahead") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField(
                    value = horizonYears.toDouble(),
                    onValueChange = { years ->
                        state.launch { state.container.settingsRepo.setHorizonYears(years.toInt().coerceIn(1, 50)) }
                    },
                    decimals = false,
                )
                Text("years")
            }
        }
        LabeledRow("Theme") {
            Dropdown(theme, ThemeChoice.entries, { it.name.lowercase() }) { choice ->
                state.launch { state.container.settingsRepo.set("theme", choice.name) }
            }
        }
    }
}
