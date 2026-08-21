package dev.fritze.skyward.ui.upcoming

import android.content.Context
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.fritze.skyward.SkywardApplication
import dev.fritze.skyward.alarm.FakeAlarmScheduler
import dev.fritze.skyward.alarm.allowNotifications
import dev.fritze.skyward.alarm.restoreRealNotificationGate
import dev.fritze.skyward.data.AppContainer
import dev.fritze.skyward.ui.awaitText
import dev.fritze.skyward.ui.awaitTextGone
import kotlinx.coroutines.runBlocking
import org.junit.After
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
    fun setUp() {
        runBlocking {
            val app = ApplicationProvider.getApplicationContext<SkywardApplication>()
            container = app.container
            container.alarmScheduler = FakeAlarmScheduler(canScheduleExact = false)
            // UpcomingScreen shows the exact-alarm card only when notifications
            // are getting through at all (§10.1: one problem at a time, the
            // fatal one first), so this suite has to pin the healthy state
            // rather than inherit whatever the emulator's notification toggle
            // happens to be.
            container.allowNotifications()
            container.settingsRepo.delete(EXACT_ALARM_DISMISSED_VERSION_KEY)
        }
    }

    // The container is a process singleton, so a substituted gate would
    // otherwise leak into every test class that runs after this one.
    @After
    fun restoreNotificationGate() {
        container.restoreRealNotificationGate(ApplicationProvider.getApplicationContext())
    }

    // Block bodies, not `= runBlocking { ... }`: an expression body's return
    // type is inferred from runBlocking's result, and these end on a
    // Compose assertion call that returns a non-Unit SemanticsNodeInteraction
    // (for chaining) -- which made the compiled test method non-void and
    // failed JUnit4's "test methods must be void" class validation,
    // aborting the whole class as an initializationError before any test ran.
    @Test
    fun dismissingCardPersistsAcrossRecomposition() {
        runBlocking {
            showUpcoming()

            composeRule.awaitText(EXACT_ALARM_CARD_TITLE)
            composeRule.onNodeWithText(EXACT_ALARM_CARD_TITLE).assertIsDisplayed()

            composeRule.onNodeWithText("Dismiss").assertIsDisplayed()
            composeRule.onNodeWithText("Dismiss").performClick()
            composeRule.awaitTextGone(EXACT_ALARM_CARD_TITLE)
            composeRule.onAllNodesWithText(EXACT_ALARM_CARD_TITLE).assertCountEquals(0)

            // A ComposeTestRule accepts exactly one setContent call per test, so
            // "recomposition" here is a pause/resume cycle -- the same trigger
            // UpcomingScreen's own LifecycleEventEffect(ON_RESUME) re-reads the
            // dismissed version on -- not a second setContent.
            composeRule.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
            composeRule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            composeRule.awaitTextGone(EXACT_ALARM_CARD_TITLE)
            composeRule.onAllNodesWithText(EXACT_ALARM_CARD_TITLE).assertCountEquals(0)
        }
    }

    @Test
    fun olderDismissedVersionStillShowsCardAfterVersionBump() {
        runBlocking {
            val currentVersionCode = appVersionCode(ApplicationProvider.getApplicationContext())
            container.settingsRepo.setExactAlarmCardDismissedVersion(currentVersionCode - 1)

            showUpcoming()

            composeRule.awaitText(EXACT_ALARM_CARD_TITLE)
            composeRule.onNodeWithText(EXACT_ALARM_CARD_TITLE).assertIsDisplayed()
        }
    }

    private fun showUpcoming() {
        composeRule.setContent {
            UpcomingScreen(container = container, onOpenEvent = {}, onOpenLocations = {})
        }
    }
}

private fun appVersionCode(context: Context): Long = runCatching {
    val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        packageInfo.longVersionCode
    } else {
        @Suppress("DEPRECATION")
        packageInfo.versionCode.toLong()
    }
}.getOrDefault(0L)
