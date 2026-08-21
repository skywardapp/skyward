package dev.fritze.skyward.ui.onboarding

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.fritze.skyward.alarm.FakeAlarmScheduler
import dev.fritze.skyward.ui.awaitText
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * §17.5 / #72: the onboarding step has to reflect the *actual* grant, not the
 * API level. On the foss flavour at API 33+ the permission is granted at
 * install, so the "we need this, go to settings" state is a lie there — and
 * after a user grants it and returns, "Not now" was the only way forward.
 */
@RunWith(AndroidJUnit4::class)
class ExactAlarmStepTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun grantedStateConfirmsAndAdvancesWithContinue() {
        var advanced = false
        composeRule.setContent {
            Column {
                ExactAlarmStep(
                    alarmScheduler = FakeAlarmScheduler(canScheduleExact = true),
                    onNext = { advanced = true },
                )
            }
        }

        composeRule.awaitText("Continue")
        composeRule.onNodeWithText("Continue").assertIsDisplayed()
        // Neither the request button nor the apologetic way out belongs on a
        // screen where there is nothing left to request.
        composeRule.onAllNodesWithText("Enable exact alarms").assertCountEquals(0)
        composeRule.onAllNodesWithText("Not now").assertCountEquals(0)

        composeRule.onNodeWithText("Continue").performClick()
        composeRule.waitForIdle()
        assertTrue(advanced, "Continue must advance the onboarding flow")
    }

    @Test
    fun ungrantedStateAsksForThePermission() {
        composeRule.setContent {
            Column {
                ExactAlarmStep(alarmScheduler = FakeAlarmScheduler(canScheduleExact = false), onNext = {})
            }
        }

        composeRule.awaitText("Enable exact alarms")
        composeRule.onNodeWithText("Enable exact alarms").assertIsDisplayed()
        composeRule.onNodeWithText("Not now").assertIsDisplayed()
        composeRule.onAllNodesWithText("Continue").assertCountEquals(0)
    }
}
