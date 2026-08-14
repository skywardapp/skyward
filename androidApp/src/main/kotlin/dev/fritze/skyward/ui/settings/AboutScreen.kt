package dev.fritze.skyward.ui.settings

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.material3.TopAppBar
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.fritze.skyward.BuildConfig

private data class Attribution(val component: String, val license: String, val note: String)

// §16's table, condensed for the About screen; the full table + NOTICE file is the source of truth.
private val ATTRIBUTIONS = listOf(
    Attribution("Astronomy Engine (Don Cross)", "MIT", "Core ephemeris calculations"),
    Attribution("Stellarium meteor-shower catalog", "GPL-2.0-or-later", "Meteor shower data from the Stellarium project"),
    Attribution("Natural Earth 1:50m", "Public domain", "Made with Natural Earth"),
    Attribution("NOAA SWPC (Kp, OVATION)", "US Gov public domain", "Aurora forecast data"),
    Attribution("NASA EONET", "US Gov / NASA open", "Event data: NASA EONET"),
    Attribution("NASA/JPL Small-Body Database", "US Gov public domain", "Comet orbital data: NASA/JPL Small-Body Database"),
    Attribution("NASA GSFC eclipse canon (Espenak & Meeus)", "Free with acknowledgment", "Eclipse predictions courtesy of Fred Espenak and Jean Meeus, NASA/GSFC"),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val packageInfo = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("About") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Skyward", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Version ${packageInfo?.versionName ?: "?"} · ${BuildConfig.FLAVOR} · ${BuildConfig.BUILD_TYPE}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "Skyward collects no personal data and transmits nothing beyond the public astronomy/space-weather " +
                    "APIs it queries. Location, if you grant it, never leaves the device.",
                style = MaterialTheme.typography.bodySmall,
            )

            OutlinedButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SOURCE_URL))) }) {
                Text("Source code (GPL-3.0-or-later)")
            }

            HorizontalDivider()
            Text("Attributions", style = MaterialTheme.typography.titleMedium)
            for (attribution in ATTRIBUTIONS) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(attribution.component, style = MaterialTheme.typography.titleSmall)
                        Text(attribution.license, style = MaterialTheme.typography.bodySmall)
                        Text(attribution.note, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

// §16/§13.1: GPL §6(d) requires Corresponding Source available from the same
// place a released build is distributed from, tagged to match versionCode --
// the actual tag-per-release wiring is R14/M7 release-engineering work; this
// points at the repository itself in the meantime.
private const val SOURCE_URL = "https://github.com/skywardapp/skyward"
