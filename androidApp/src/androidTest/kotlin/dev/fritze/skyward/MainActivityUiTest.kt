package dev.fritze.skyward

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * M0 acceptance check (§18), asserted on a real device instead of by eye:
 * "hello-world Compose app boots". Runs against both flavours (§17.5) — the
 * foss/play split must never change what the UI does (D13).
 *
 * This is deliberately thin: it exists so the instrumented-test harness and its
 * CI emulator job are in place and green before M3 lands the screens that need
 * real coverage (§17.5: alarm/notification paths, exact-alarm fallback, SAF
 * round-trip).
 */
@RunWith(AndroidJUnit4::class)
class MainActivityUiTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun appBootsAndRendersItsName() {
        composeRule.onNodeWithText("Skyward").assertIsDisplayed()
    }

    @Test
    fun uiSurvivesActivityRecreation() {
        composeRule.activityRule.scenario.recreate()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Skyward").assertIsDisplayed()
    }
}
