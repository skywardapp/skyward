package dev.fritze.skyward.desktop.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.fritze.skyward.desktop.APP_VERSION
import dev.fritze.skyward.desktop.DesktopBuildInfo
import dev.fritze.skyward.desktop.ui.common.SectionCard
import dev.fritze.skyward.desktop.ui.common.openInBrowser
import dev.fritze.skyward.core.format.EONET_ATTRIBUTION_NOTE
import dev.fritze.skyward.core.format.PRIVACY_POLICY_URL
import dev.fritze.skyward.core.format.sourceRepositoryUrl

/**
 * §16: "`NOTICE` file enumerates the rows below; About screen renders them."
 * The NOTICE file itself is packaged as a resource so this can never drift
 * from the file the repository ships.
 */
@Composable
internal fun AboutSection() {
    var showNotice by remember { mutableStateOf(false) }
    val notice = remember { loadNotice() }

    SectionCard("About") {
        Text("Skyward $APP_VERSION", style = MaterialTheme.typography.bodyLarge)
        Text(
            "Free software under the GNU General Public License, version 3 or later.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "Event data from NOAA SWPC, NASA EONET and JPL. Map vectors: made with Natural Earth. " +
                "Meteor shower catalog from the Stellarium project.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            EONET_ATTRIBUTION_NOTE,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { showNotice = !showNotice }) {
                Text(if (showNotice) "Hide third-party notices" else "Third-party notices")
            }
            TextButton(onClick = { openInBrowser(PRIVACY_POLICY_URL) }) { Text("Privacy policy") }
            // §16/GPL §6(d): the corresponding source has to be reachable from
            // the same place the app is, and the About screen is where a user
            // will look for it.
            TextButton(onClick = { openInBrowser(sourceRepositoryUrl(DesktopBuildInfo.releaseTag)) }) { Text("Source code") }
        }
        if (showNotice) {
            Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                Text(notice, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

private fun loadNotice(): String =
    AboutSectionMarker::class.java.getResourceAsStream("/NOTICE")?.use { it.readBytes().decodeToString() }
        ?: "NOTICE file not bundled in this build."

/** Anchor for the classloader lookup above — `object` rather than a top-level `::class` dance. */
private object AboutSectionMarker
