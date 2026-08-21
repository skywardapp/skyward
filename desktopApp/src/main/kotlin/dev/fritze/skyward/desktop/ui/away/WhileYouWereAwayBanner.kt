package dev.fritze.skyward.desktop.ui.away

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.fritze.skyward.core.format.formatDateTime
import dev.fritze.skyward.desktop.ui.DesktopAppState

/**
 * §10.3: "Missed-while-closed events: on startup, list matches whose anchor
 * passed while the app was closed in a 'While you were away' panel instead
 * of firing stale notifications."
 *
 * A banner rather than a modal: the reminders it lists have already happened,
 * so nothing about it should block getting on with the app.
 */
@Composable
fun WhileYouWereAwayBanner(state: DesktopAppState) {
    val missed by state.missedWhileAway.collectAsState()
    if (missed.isEmpty()) return

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 2.dp,
    ) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    // Inline English plural, deliberately: see
                    // docs/adr/0012-english-only-ui-strings.md.
                    "While you were away — ${missed.size} reminder${if (missed.size == 1) "" else "s"} came due",
                    style = MaterialTheme.typography.titleSmall,
                )
                TextButton(onClick = state::dismissMissedWhileAway) { Text("Dismiss") }
            }
            Column(
                modifier = Modifier.heightIn(max = 180.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                for (reminder in missed) {
                    val notification = reminder.notification
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { state.openOccurrence(notification.occurrenceId) }
                            .padding(vertical = 2.dp),
                    ) {
                        Text(
                            "${formatDateTime(notification.fireAt, state.zone)} — ${notification.title}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            notification.body,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
