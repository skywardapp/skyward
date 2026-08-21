package dev.fritze.skyward.ui.upcoming

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.fritze.skyward.SkywardApplication
import dev.fritze.skyward.alarm.AndroidNotificationGate
import dev.fritze.skyward.alarm.FakeAlarmScheduler
import dev.fritze.skyward.alarm.NotificationGate
import dev.fritze.skyward.data.AppContainer
import dev.fritze.skyward.ui.awaitText
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * §17.5/§10.1: the denial that costs the user every reminder has to be
 * visible on the screen they actually open, not just in system settings.
 * The exact-alarm permission is *also* withheld here, so the test pins down
 * that the fatal warning replaces the merely-degraded one rather than
 * stacking with it.
 */
@RunWith(AndroidJUnit4::class)
class NotificationsBlockedCardTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var container: AppContainer

    @Before
    fun setUp() {
        runBlocking {
            val app = ApplicationProvider.getApplicationContext<SkywardApplication>()
            container = app.container
            container.notificationGate = NotificationGate { false }
            container.alarmScheduler = FakeAlarmScheduler(canScheduleExact = false)
            container.settingsRepo.delete(EXACT_ALARM_DISMISSED_VERSION_KEY)
        }
    }

    // The container is a process singleton; a blocked gate left behind would
    // make every later test class believe notifications are off.
    @After
    fun restoreNotificationGate() {
        container.notificationGate = AndroidNotificationGate(ApplicationProvider.getApplicationContext())
    }

    // Block body, not an expression body: see the note in
    // ExactAlarmDegradationCardTest about JUnit4's "test methods must be void".
    @Test
    fun blockedNotificationsWarnAndOfferTheSystemSettingsRoute() {
        runBlocking {
            showUpcoming()

            composeRule.awaitText(BLOCKED_CARD_TITLE)
            composeRule.onNodeWithText(BLOCKED_CARD_TITLE).assertIsDisplayed()
            composeRule.onNodeWithText("Turn notifications on").assertIsDisplayed()
        }
    }

    @Test
    fun blockedNotificationsSuppressTheExactAlarmCardAndOfferNoDismiss() {
        runBlocking {
            showUpcoming()

            composeRule.awaitText(BLOCKED_CARD_TITLE)
            composeRule.onAllNodesWithText(EXACT_ALARM_CARD_TITLE).assertCountEquals(0)
            // Dismissing would restore the silent failure the card exists to end.
            composeRule.onAllNodesWithText("Dismiss").assertCountEquals(0)
        }
    }

    private fun showUpcoming() {
        composeRule.setContent {
            UpcomingScreen(container = container, onOpenEvent = {})
        }
    }
}

private const val BLOCKED_CARD_TITLE = "Notifications are blocked"
private const val EXACT_ALARM_CARD_TITLE = "Exact alarms are off"
private const val EXACT_ALARM_DISMISSED_VERSION_KEY = "exact_alarm_card_dismissed_version"
