package dev.fritze.skyward.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.fritze.skyward.BuildConfig

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val packageInfo = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
    var showNotice by remember { mutableStateOf(false) }
    val notice = remember(context) { loadNotice(context) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("About") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
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
            Text(
                "Event data from NOAA SWPC, NASA EONET and NASA/JPL. Eclipse predictions courtesy of Fred Espenak " +
                    "and Jean Meeus, NASA/GSFC. Meteor shower catalog from the Stellarium project. Ephemeris " +
                    "calculations by Astronomy Engine (Don Cross). Made with Natural Earth.",
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(onClick = { showNotice = !showNotice }) {
                Text(if (showNotice) "Hide third-party notices" else "Third-party notices")
            }
            if (showNotice) {
                Card(Modifier.fillMaxWidth()) {
                    Text(
                        notice,
                        Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
    }
}

/**
 * §16: "`NOTICE` file enumerates the rows below; About screen renders them."
 *
 * The screen used to carry its own condensed retyping of §16's table, which
 * made two sources of truth for a licence obligation — the one place a
 * divergence is least acceptable and least likely to be noticed. The
 * repository's `NOTICE` is packaged as an asset instead (see `bundleNotice`
 * in androidApp/build.gradle.kts), exactly as the desktop app packages it as
 * a resource, so both frontends render the file the repository ships.
 */
private fun loadNotice(context: Context): String =
    runCatching { context.assets.open("NOTICE").use { it.readBytes().decodeToString() } }
        .getOrElse { "NOTICE file not bundled in this build." }

// §16/§13.1: GPL §6(d) requires Corresponding Source available from the same
// place a released build is distributed from, tagged to match versionCode --
// the actual tag-per-release wiring is R14/M7 release-engineering work; this
// points at the repository itself in the meantime.
private const val SOURCE_URL = "https://github.com/skywardapp/skyward"
