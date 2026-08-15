package dev.fritze.skyward.ui.upcoming

import android.content.Context
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.fritze.skyward.SkywardApplication
import dev.fritze.skyward.alarm.FakeAlarmScheduler
import dev.fritze.skyward.data.AppContainer
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExactAlarmDegradationCardTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var container: AppContainer

    @Before
    fun setUp() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<SkywardApplication>()
        container = app.container
        container.alarmScheduler = FakeAlarmScheduler(canScheduleExact = false)
        container.settingsRepo.delete(EXACT_ALARM_DISMISSED_VERSION_KEY)
    }

    @Test
    fun dismissingCardPersistsAcrossRecomposition() = runBlocking {
        showUpcoming()

        composeRule.awaitText(EXACT_ALARM_CARD_TITLE)
        composeRule.onNodeWithText(EXACT_ALARM_CARD_TITLE).assertIsDisplayed()

        composeRule.onNodeWithText("Dismiss").assertIsDisplayed()
        composeRule.onNodeWithText("Dismiss").performClick()
        composeRule.awaitTextGone(EXACT_ALARM_CARD_TITLE)
        composeRule.onNodeWithText(EXACT_ALARM_CARD_TITLE).assertDoesNotExist()

        showUpcoming()
        composeRule.awaitTextGone(EXACT_ALARM_CARD_TITLE)
        composeRule.onNodeWithText(EXACT_ALARM_CARD_TITLE).assertDoesNotExist()
    }

    @Test
    fun olderDismissedVersionStillShowsCardAfterVersionBump() = runBlocking {
        val currentVersionCode = appVersionCode(ApplicationProvider.getApplicationContext())
        container.settingsRepo.setExactAlarmCardDismissedVersion(currentVersionCode - 1)

        showUpcoming()

        composeRule.awaitText(EXACT_ALARM_CARD_TITLE)
        composeRule.onNodeWithText(EXACT_ALARM_CARD_TITLE).assertIsDisplayed()
    }

    private fun showUpcoming() {
        composeRule.setContent {
            UpcomingScreen(container = container, onOpenEvent = {})
        }
    }
}

private const val EXACT_ALARM_CARD_TITLE = "Exact alarms are off"
private const val EXACT_ALARM_DISMISSED_VERSION_KEY = "exact_alarm_card_dismissed_version"

private fun appVersionCode(context: Context): Long = runCatching {
    val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        packageInfo.longVersionCode
    } else {
        @Suppress("DEPRECATION")
        packageInfo.versionCode.toLong()
    }
}.getOrDefault(0L)

private fun ComposeTestRule.awaitText(text: String) {
    waitUntil(timeoutMillis = 10_000) {
        onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }
}

private fun ComposeTestRule.awaitTextGone(text: String) {
    waitUntil(timeoutMillis = 10_000) {
        onAllNodesWithText(text).fetchSemanticsNodes().isEmpty()
    }
}
