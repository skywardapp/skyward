package dev.fritze.skyward.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.fritze.skyward.desktop.ui.aurora.AuroraDashboardScreen
import dev.fritze.skyward.desktop.ui.away.NotifierUnavailableBanner
import dev.fritze.skyward.desktop.ui.away.WhileYouWereAwayBanner
import dev.fritze.skyward.desktop.ui.map.EventMapScreen
import dev.fritze.skyward.desktop.ui.overview.OverviewScreen
import dev.fritze.skyward.desktop.ui.rules.RulesScreen
import dev.fritze.skyward.desktop.ui.settings.SettingsScreen
import dev.fritze.skyward.desktop.ui.skychart.SkyChartScreen
import dev.fritze.skyward.desktop.ui.theme.SkywardTheme
import dev.fritze.skyward.desktop.ui.timeline.TimelineScreen

/**
 * §14: single window, left nav rail, one screen at a time. Rules/Settings/
 * EventDetail reuse the same core computation as Android with desktop
 * layouts (two-pane where natural).
 */
@Composable
fun SkywardApp(state: DesktopAppState) {
    val theme by state.theme.collectAsState()
    SkywardTheme(theme) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Row(modifier = Modifier.fillMaxSize()) {
                SkywardNavigationRail(state)
                // weight(1f), not fillMaxSize(): an unweighted Row child asking
                // to fill the whole row leaves the layout no width to give it
                // after the rail has taken its share.
                Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    // §10.3: surfaced above whatever screen is open rather than
                    // as its own destination — it's a one-shot startup report,
                    // not a place in the app.
                    WhileYouWereAwayBanner(state)
                    // Above the startup panel's own placement and equally
                    // screen-independent: "your reminders aren't reaching you"
                    // is true wherever the user happens to be looking.
                    NotifierUnavailableBanner(state)
                    Box(modifier = Modifier.fillMaxSize()) {
                        when (state.destination) {
                            Destination.OVERVIEW -> OverviewScreen(state)
                            Destination.MAP -> EventMapScreen(state)
                            Destination.TIMELINE -> TimelineScreen(state)
                            Destination.SKY_CHART -> SkyChartScreen(state)
                            Destination.AURORA -> AuroraDashboardScreen(state)
                            Destination.RULES -> RulesScreen(state)
                            Destination.SETTINGS -> SettingsScreen(state)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SkywardNavigationRail(state: DesktopAppState) {
    NavigationRail(
        containerColor = MaterialTheme.colorScheme.surface,
        header = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(vertical = 12.dp)) {
                Text("Skyward", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
            }
        },
    ) {
        // Deliberately NOT fillMaxSize/fillMaxWidth: NavigationRail sizes
        // itself to its content, so a child that asks to fill the maximum
        // width stretches the rail across the entire window and squeezes the
        // screen beside it down to nothing.
        Column(
            modifier = Modifier.fillMaxHeight().padding(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            for (destination in Destination.entries) {
                NavigationRailItem(
                    selected = state.destination == destination,
                    onClick = { state.navigateTo(destination) },
                    // Glyphs rather than Material icons: the icon set is a
                    // separate artifact and none of its symbols mean "sky
                    // chart" or "aurora" anyway.
                    icon = { Text(destination.glyph, style = MaterialTheme.typography.titleMedium) },
                    label = { Text(destination.title, style = MaterialTheme.typography.labelSmall) },
                    alwaysShowLabel = true,
                )
            }
        }
    }
}
