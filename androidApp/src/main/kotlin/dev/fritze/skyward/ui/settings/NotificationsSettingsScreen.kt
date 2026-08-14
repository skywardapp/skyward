package dev.fritze.skyward.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.fritze.skyward.data.AppContainer

/** §13.1/§10.1: channels shortcut, the honesty explainer, and exact-alarm permission state. Quiet hours are per-rule (§9.1), editable via the full RuleEditor (M5) -- shown read-only here. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationsSettingsScreen(container: AppContainer, onBack: () -> Unit) {
    val context = LocalContext.current
    var canScheduleExact by remember { mutableStateOf(container.alarmScheduler.canScheduleExact()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Notifications") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("How reminders are delivered", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Exact: eclipses, showers, supermoons, conjunctions fire at the precise minute. " +
                            "Approximate: the same events, but the OS may delay delivery by up to tens of " +
                            "minutes because exact alarms aren't enabled. Best-effort: aurora, comets, and " +
                            "Earth events depend on polling and can lag behind by ~15 minutes or more.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Exact alarms", style = MaterialTheme.typography.titleSmall)
                    Text(
                        if (canScheduleExact) "Enabled — reminders fire at the precise minute." else "Disabled — reminders will be approximate.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (!canScheduleExact && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        Button(onClick = {
                            context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")))
                        }) { Text("Enable exact alarms") }
                    }
                }
            }

            OutlinedButton(onClick = {
                val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                context.startActivity(intent)
            }) { Text("Open system notification settings") }
        }
    }
}
