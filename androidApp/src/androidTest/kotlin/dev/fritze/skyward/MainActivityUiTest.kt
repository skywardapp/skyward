package dev.fritze.skyward

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Boot check for the single-Activity shell (§13.1), asserted on a real
 * device instead of by eye. Runs against both flavours (§17.5) -- the
 * foss/play split must never change what the UI does (D13).
 *
 * Was the M0 "hello-world Compose app boots" check; M3 replaced that
 * placeholder screen with the real NavHost, so this now asserts the
 * genuine first-run destination, and drives the whole §13.1 onboarding
 * flow end to end -- welcome -> location -> notification permission ->
 * exact-alarm explainer -> rules preview -> Upcoming -- rather than only
 * the welcome step a user sees first.
 */
@RunWith(AndroidJUnit4::class)
class MainActivityUiTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val context = ApplicationProvider.getApplicationContext<SkywardApplication>()

    // connectedAndroidTest runs every test method in this process without clearing
    // app data between them (no test orchestrator configured), so a method that
    // finishes onboarding -- and, in the manual-location test, saves a location --
    // would otherwise leak state into whichever method the runner picks next.
    // Force onboarding back to unset, drop any saved locations, and recreate
    // before every test so each one starts from a genuine "fresh install"
    // regardless of run order or what a previous method left behind.
    @Before
    fun startWithOnboardingIncomplete() {
        runBlocking {
            context.container.settingsRepo.setOnboardingDone(false)
            for (location in context.container.locationRepo.getAll()) {
                context.container.locationRepo.delete(location.id)
            }
        }
        composeRule.activityRule.scenario.recreate()
    }

    @Test
    fun appBootsIntoOnboardingOnAFreshInstall() {
        composeRule.awaitText(WELCOME_HEADLINE)

        composeRule.onNodeWithText(WELCOME_HEADLINE).assertIsDisplayed()
        composeRule.onNodeWithText("Get started").assertIsDisplayed()
    }

    @Test
    fun uiSurvivesActivityRecreation() {
        composeRule.awaitText(WELCOME_HEADLINE)

        composeRule.activityRule.scenario.recreate()

        composeRule.awaitText(WELCOME_HEADLINE)
        composeRule.onNodeWithText(WELCOME_HEADLINE).assertIsDisplayed()
    }

    /**
     * Drives every §13.1 onboarding step through its app-side "proceed"
     * action -- entering a manual location rather than granting the
     * coarse-location permission, and skipping the notification/exact-alarm
     * permission prompts rather than driving the real OS dialogs an
     * instrumented test can't click deterministically (§10.2 promises the
     * app stays fully usable either way) -- and asserts it lands on
     * Upcoming with the bottom bar, then that a relaunch (simulated via
     * recreate, as [uiSurvivesActivityRecreation] does) skips straight back
     * to Upcoming instead of onboarding again.
     */
    @Test
    fun userCompletesOnboardingWithALocationAndReachesUpcoming() {
        composeRule.awaitText(WELCOME_HEADLINE)
        composeRule.onNodeWithText("Get started").performClick()

        composeRule.awaitText(LOCATION_HEADLINE)
        composeRule.onNodeWithText("Latitude").performTextInput("52.52")
        composeRule.onNodeWithText("Longitude").performTextInput("13.405")
        composeRule.onNodeWithText("Continue").performClick()

        composeRule.awaitText(NOTIFICATIONS_HEADLINE)
        composeRule.dismissOsPrompt()

        composeRule.awaitText(EXACT_ALARM_HEADLINE)
        composeRule.dismissOsPrompt()

        composeRule.awaitText(RULES_PREVIEW_HEADLINE)
        composeRule.onNodeWithText("• Supermoon").assertIsDisplayed()
        composeRule.onNodeWithText("Start using Skyward").performClick()

        composeRule.assertOnUpcoming()

        composeRule.activityRule.scenario.recreate()

        composeRule.assertOnUpcoming()
    }

    /**
     * §10.2: "the app must be fully usable without ever granting" location
     * permission -- skipping every optional step (no saved location, no
     * notification permission, no exact-alarm permission) must still
     * complete onboarding and hand off to Upcoming, exercising
     * `OnboardingViewModel.finish`'s locationJob-is-null path that
     * [userCompletesOnboardingWithALocationAndReachesUpcoming] never
     * reaches, and must survive a relaunch just the same.
     */
    @Test
    fun userCanSkipEveryOptionalStepAndStillReachUpcoming() {
        composeRule.awaitText(WELCOME_HEADLINE)
        composeRule.onNodeWithText("Get started").performClick()

        composeRule.awaitText(LOCATION_HEADLINE)
        composeRule.onNodeWithText("Skip for now").performClick()

        composeRule.awaitText(NOTIFICATIONS_HEADLINE)
        composeRule.dismissOsPrompt()

        composeRule.awaitText(EXACT_ALARM_HEADLINE)
        composeRule.dismissOsPrompt()

        composeRule.awaitText(RULES_PREVIEW_HEADLINE)
        composeRule.onNodeWithText("Start using Skyward").performClick()

        composeRule.assertOnUpcoming()

        composeRule.activityRule.scenario.recreate()

        composeRule.assertOnUpcoming()
    }
}

private const val WELCOME_HEADLINE = "Welcome to Skyward"
private const val LOCATION_HEADLINE = "Add your first location"
private const val NOTIFICATIONS_HEADLINE = "Stay in the loop"
private const val EXACT_ALARM_HEADLINE = "Precise timing"
private const val RULES_PREVIEW_HEADLINE = "Default reminders"

/**
 * `waitForIdle()` is not enough here: MainActivity renders an empty gate
 * until `observeOnboardingDone()` emits, and that first emission comes off
 * a database read on a background dispatcher that Compose's idling
 * resource knows nothing about -- so the tree can be legitimately "idle"
 * while still showing nothing.
 */
private fun androidx.compose.ui.test.junit4.ComposeTestRule.awaitText(text: String) {
    waitUntil(timeoutMillis = 10_000) {
        onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }
}

/**
 * Advances past the notification-permission / exact-alarm steps without
 * touching real OS chrome (a system permission dialog, or the Settings
 * activity the exact-alarm intent opens) that an instrumented test running
 * in-process can't click deterministically: "Not now" where the step
 * offers it, otherwise "Continue" -- the label these steps fall back to
 * once the permission is already implied by the OS version (§10.2 notes
 * both are conditional on API level) or already granted.
 */
private fun androidx.compose.ui.test.junit4.ComposeTestRule.dismissOsPrompt() {
    if (onAllNodesWithText("Not now").fetchSemanticsNodes().isNotEmpty()) {
        onNodeWithText("Not now").performClick()
    } else {
        onNodeWithText("Continue").performClick()
    }
}

/**
 * "Upcoming" is on screen twice once onboarding hands off -- the bottom
 * bar's tab label and the TopAppBar title -- so this asserts via
 * [awaitText] (which tolerates any match count) rather than
 * `onNodeWithText`, which requires exactly one. The bottom-bar-only
 * "Rules"/"Settings" labels confirm the destination is specifically
 * Upcoming and not still onboarding.
 */
private fun androidx.compose.ui.test.junit4.ComposeTestRule.assertOnUpcoming() {
    awaitText("Upcoming")
    onNodeWithText("Rules").assertIsDisplayed()
    onNodeWithText("Settings").assertIsDisplayed()
}
