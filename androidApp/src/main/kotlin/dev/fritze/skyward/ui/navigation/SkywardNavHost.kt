package dev.fritze.skyward.ui.navigation

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dev.fritze.skyward.data.AppContainer
import dev.fritze.skyward.ui.eventdetail.EventDetailScreen
import dev.fritze.skyward.ui.locations.LocationEditorScreen
import dev.fritze.skyward.ui.locations.LocationsScreen
import dev.fritze.skyward.ui.onboarding.OnboardingScreen
import dev.fritze.skyward.ui.rules.RuleEditorScreen
import dev.fritze.skyward.ui.rules.RulesScreen
import dev.fritze.skyward.ui.settings.AboutScreen
import dev.fritze.skyward.ui.settings.NotificationsSettingsScreen
import dev.fritze.skyward.ui.settings.SettingsScreen
import dev.fritze.skyward.ui.settings.SourcesScreen
import dev.fritze.skyward.ui.settings.SyncScreen
import dev.fritze.skyward.ui.upcoming.UpcomingScreen

/** §13.1: BottomBar [Upcoming] [Rules] [Settings] -- Map is v1.1, hidden behind a flag (not built at all yet). */
@Composable
fun SkywardNavHost(
    container: AppContainer,
    onboardingDone: Boolean,
    tappedOccurrenceId: String?,
    onTapConsumed: () -> Unit,
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    // A tapped reminder (§10.2) lands here. Onboarding gates it: until it is
    // done the graph starts at ONBOARDING, and pushing a detail screen on top
    // of the welcome flow would strand a first-run user with a Back button
    // that returns them to it — so a tap arriving mid-onboarding is held (the
    // effect re-runs when the flag flips) rather than dropped.
    LaunchedEffect(tappedOccurrenceId, onboardingDone) {
        if (tappedOccurrenceId != null && onboardingDone) {
            navController.navigate(Routes.eventDetail(tappedOccurrenceId)) { launchSingleTop = true }
            onTapConsumed()
        }
    }

    Scaffold(
        bottomBar = {
            if (currentRoute in Routes.BOTTOM_BAR_ROUTES) {
                NavigationBar {
                    NavigationBarItem(
                        selected = currentRoute == Routes.UPCOMING,
                        onClick = { navController.navigateToTopLevel(Routes.UPCOMING) },
                        icon = { Icon(Icons.Filled.Star, contentDescription = null) },
                        label = { Text("Upcoming") },
                    )
                    NavigationBarItem(
                        selected = currentRoute == Routes.RULES,
                        onClick = { navController.navigateToTopLevel(Routes.RULES) },
                        icon = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = null) },
                        label = { Text("Rules") },
                    )
                    NavigationBarItem(
                        selected = currentRoute == Routes.SETTINGS,
                        onClick = { navController.navigateToTopLevel(Routes.SETTINGS) },
                        icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                        label = { Text("Settings") },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = if (onboardingDone) Routes.UPCOMING else Routes.ONBOARDING,
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            composable(Routes.ONBOARDING) {
                OnboardingScreen(container) {
                    navController.navigate(Routes.UPCOMING) { popUpTo(Routes.ONBOARDING) { inclusive = true } }
                }
            }
            composable(Routes.UPCOMING) {
                UpcomingScreen(container, onOpenEvent = { navController.navigate(Routes.eventDetail(it)) })
            }
            composable(Routes.EVENT_DETAIL) { entry ->
                val occurrenceId = entry.arguments?.getString(Routes.EVENT_DETAIL_ARG).orEmpty()
                EventDetailScreen(container, occurrenceId, onBack = { navController.popBackStack() })
            }
            composable(Routes.RULES) {
                RulesScreen(
                    container,
                    onAdd = { navController.navigate(Routes.RULE_EDITOR_NEW) },
                    onEdit = { navController.navigate(Routes.ruleEditor(it)) },
                )
            }
            composable(Routes.RULE_EDITOR_NEW) {
                RuleEditorScreen(container, ruleId = null, onDone = { navController.popBackStack() })
            }
            composable(Routes.RULE_EDITOR_EDIT) { entry ->
                val ruleId = entry.arguments?.getString(Routes.RULE_EDITOR_ARG)
                RuleEditorScreen(container, ruleId, onDone = { navController.popBackStack() })
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    onLocations = { navController.navigate(Routes.LOCATIONS) },
                    onNotifications = { navController.navigate(Routes.NOTIFICATIONS_SETTINGS) },
                    onSources = { navController.navigate(Routes.SOURCES) },
                    onSync = { navController.navigate(Routes.SYNC) },
                    onAbout = { navController.navigate(Routes.ABOUT) },
                )
            }
            composable(Routes.LOCATIONS) {
                LocationsScreen(
                    container,
                    onBack = { navController.popBackStack() },
                    onAdd = { navController.navigate(Routes.LOCATION_EDITOR_NEW) },
                    onEdit = { navController.navigate(Routes.locationEditor(it)) },
                )
            }
            composable(Routes.LOCATION_EDITOR_NEW) {
                LocationEditorScreen(container, locationId = null, onDone = { navController.popBackStack() })
            }
            composable(Routes.LOCATION_EDITOR_EDIT) { entry ->
                val locationId = entry.arguments?.getString(Routes.LOCATION_EDITOR_ARG)
                LocationEditorScreen(container, locationId, onDone = { navController.popBackStack() })
            }
            composable(Routes.NOTIFICATIONS_SETTINGS) { NotificationsSettingsScreen(container, onBack = { navController.popBackStack() }) }
            composable(Routes.SOURCES) { SourcesScreen(container, onBack = { navController.popBackStack() }) }
            composable(Routes.SYNC) { SyncScreen(container, onBack = { navController.popBackStack() }) }
            composable(Routes.ABOUT) { AboutScreen(onBack = { navController.popBackStack() }) }
        }
    }
}

private fun androidx.navigation.NavController.navigateToTopLevel(route: String) {
    // Routes.UPCOMING, not graph.findStartDestination().id: the bottom bar (and thus this
    // function) is only ever shown once onboarding is done, but the graph's start destination
    // is still ONBOARDING for the composition that removed it, so findStartDestination() can
    // resolve to a route that's no longer on the back stack and silently no-op the popUpTo.
    navigate(route) {
        popUpTo(Routes.UPCOMING) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
