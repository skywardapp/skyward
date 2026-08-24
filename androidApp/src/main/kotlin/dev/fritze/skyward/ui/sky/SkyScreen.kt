package dev.fritze.skyward.ui.sky

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.fritze.skyward.data.AppContainer
import dev.fritze.skyward.ui.sky.aurora.AuroraDashboardTab
import dev.fritze.skyward.ui.sky.map.EventMapTab
import dev.fritze.skyward.ui.sky.skychart.SkyChartTab
import dev.fritze.skyward.ui.sky.timeline.TimelineTab

/**
 * §14.1-§14.4's four drawn views, behind one bottom-bar destination.
 *
 * §13.1's bottom bar has four items and Material 3's `NavigationBar` tops
 * out around five, so the views share the slot §13.1 reserved for Map with
 * a tab row rather than taking one each — ADR 0025. The desktop puts the
 * same four on a nav rail, where there is room. ADR 0022 is why Android
 * has all four at all.
 */
enum class SkyTab(val label: String) {
    MAP("Map"),
    TIMELINE("Timeline"),
    CHART("Sky chart"),
    AURORA("Aurora"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkyScreen(container: AppContainer, onOpenEvent: (String) -> Unit) {
    val viewModel: SkyViewModel = viewModel { SkyViewModel(container) }
    val state by viewModel.uiState.collectAsState()

    // rememberSaveable so the tab survives rotation and process death: these
    // are exploratory views, and losing the one you were reading because the
    // screen turned is a small betrayal.
    var tab by rememberSaveable { mutableStateOf(SkyTab.MAP) }

    // Advanced at each five-minute recompute boundary, not per frame: these
    // views span days and years, so a live tick would re-run three-year
    // horizon passes for no visible change — but freezing it outright would
    // leave the aurora forecast and darkness windows stale on a screen left
    // open.
    val now by rememberChartNow(viewModel::now)

    // §14.4: "Refresh: dashboard-open forces active polling tier (§7.3.2)."
    // Keyed on the tab, so opening Aurora refreshes and switching away and
    // back refreshes again — but merely recomposing does not.
    LaunchedEffect(tab) {
        if (tab == SkyTab.AURORA) viewModel.refreshAurora()
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Sky") }) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            PrimaryTabRow(selectedTabIndex = tab.ordinal) {
                for (candidate in SkyTab.entries) {
                    Tab(
                        selected = candidate == tab,
                        onClick = { tab = candidate },
                        text = { Text(candidate.label, style = MaterialTheme.typography.labelLarge) },
                    )
                }
            }

            if (state.isLoading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                return@Column
            }

            when (tab) {
                SkyTab.MAP -> EventMapTab(state, onOpenEvent)
                SkyTab.TIMELINE -> TimelineTab(container, state, viewModel.zone, now, onOpenEvent)
                SkyTab.CHART -> SkyChartTab(state, viewModel.zone, now, onOpenEvent)
                SkyTab.AURORA -> AuroraDashboardTab(state, viewModel.zone, now)
            }
        }
    }
}
