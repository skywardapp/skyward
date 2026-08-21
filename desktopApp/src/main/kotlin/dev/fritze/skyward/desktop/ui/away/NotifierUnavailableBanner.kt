package dev.fritze.skyward.desktop.ui.away

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import dev.fritze.skyward.desktop.ui.DesktopAppState
import dev.fritze.skyward.desktop.ui.Destination

/**
 * §10.1's honesty contract on the desktop: a reminder came due, §10.3's
 * DBus → `notify-send` chain refused it, and the row was still marked FIRED
 * because its moment has passed and re-posting it forever would be worse.
 * The scheduler writes that to stderr, which nobody reads — so without this
 * the user's only route to the fact is guessing to press "Send a test
 * notification" in Settings (#79).
 *
 * Dismissable, unlike Android's notifications-blocked card: this is a report
 * about a reminder that already happened rather than a standing state we can
 * re-check, so refusing dismissal would pin it there for the session. The
 * next undeliverable reminder raises it again, and a successful delivery
 * (including a Settings test) retracts it.
 */
@Composable
fun NotifierUnavailableBanner(state: DesktopAppState) {
    val unavailable by state.notifierUnavailable.collectAsState()
    if (!unavailable) return

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.errorContainer,
        tonalElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.padding(end = 12.dp)) {
                Text("Your desktop can't show Skyward's reminders", style = MaterialTheme.typography.titleSmall)
                Text(
                    "A reminder came due and no notification backend would display it. Skyward keeps " +
                        "planning and listing them, but you will only see them in this window.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { state.navigateTo(Destination.SETTINGS) }) { Text("Open Settings") }
                TextButton(onClick = state::dismissNotifierUnavailable) { Text("Dismiss") }
            }
        }
    }
}
