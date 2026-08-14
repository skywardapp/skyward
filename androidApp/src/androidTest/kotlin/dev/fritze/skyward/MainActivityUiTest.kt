package dev.fritze.skyward

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Boot check for the single-Activity shell (§13.1), asserted on a real device
 * instead of by eye. Runs against both flavours (§17.5) — the foss/play split
 * must never change what the UI does (D13).
 *
 * Was the M0 "hello-world Compose app boots" check; M3 replaced that placeholder
 * screen with the real NavHost, so this now asserts the genuine first-run
 * destination. On a freshly installed app `onboarding_done` is unset, so
 * [MainActivity] gates the NavHost onto the onboarding flow (§13.1) and the
 * welcome step is what a user actually sees first.
 */
@RunWith(AndroidJUnit4::class)
class MainActivityUiTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

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
}

private const val WELCOME_HEADLINE = "Welcome to Skyward"

/**
 * `waitForIdle()` is not enough here: MainActivity renders an empty gate until
 * `observeOnboardingDone()` emits, and that first emission comes off a database
 * read on a background dispatcher that Compose's idling resource knows nothing
 * about — so the tree can be legitimately "idle" while still showing nothing.
 */
private fun androidx.compose.ui.test.junit4.ComposeTestRule.awaitText(text: String) {
    waitUntil(timeoutMillis = 10_000) {
        onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }
}
