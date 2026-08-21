package dev.fritze.skyward.alarm

import android.content.Intent
import android.os.SystemClock
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.fritze.skyward.MainActivity
import dev.fritze.skyward.SkywardApplication
import dev.fritze.skyward.core.model.Certainty
import dev.fritze.skyward.core.model.CometElements
import dev.fritze.skyward.core.model.CometMagParams
import dev.fritze.skyward.core.model.CometPayload
import dev.fritze.skyward.core.model.MoonEventKind
import dev.fritze.skyward.core.model.MoonEventPayload
import dev.fritze.skyward.core.model.Occurrence
import dev.fritze.skyward.core.model.Phenomenon
import dev.fritze.skyward.core.model.TimeWindow
import dev.fritze.skyward.ui.awaitText
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/**
 * §17.5: what happens when a fired reminder is tapped (#51). The unit test
 * beside this one covers the action encoding; this covers the half only a
 * device can answer -- that the intent really does start [MainActivity] and
 * land on the tapped occurrence's detail screen, whether the app was closed
 * or already open.
 *
 * Launches the Activity itself (hence [createEmptyComposeRule]) because the
 * launching intent is the thing under test, and asserts through
 * [awaitText]: the detail screen only names its occurrence once the database
 * read behind it emits, which Compose's idling resource knows nothing about,
 * so `waitForIdle()` alone can return with "Loading…" still on screen.
 */
@RunWith(AndroidJUnit4::class)
class NotificationTapTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val context = ApplicationProvider.getApplicationContext<SkywardApplication>()
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun seedOccurrences() {
        runBlocking {
            // A reminder can only fire after onboarding, so this is the state
            // the feature actually runs in.
            context.container.settingsRepo.setOnboardingDone(true)
            context.container.occurrenceRepo.upsert(supermoon, NOW)
            context.container.occurrenceRepo.upsert(comet, NOW)
        }
    }

    /**
     * connectedAndroidTest runs every method in one process against one
     * database, with no orchestrator clearing app data in between (the same
     * hazard MainActivityUiTest documents), so what this class writes has to
     * be taken back out again: left behind, the seeded occurrences and a
     * completed-onboarding flag would decide the outcome of some later
     * first-run or empty-list test.
     */
    @After
    fun restoreSharedState() {
        scenario?.close()
        runBlocking {
            context.container.occurrenceRepo.deleteById(supermoon.id)
            context.container.occurrenceRepo.deleteById(comet.id)
            context.container.settingsRepo.setOnboardingDone(false)
        }
    }

    @Test
    fun tappingAReminderOnAClosedAppOpensItsEvent() {
        scenario = ActivityScenario.launch(openEventIntent(supermoon.id))

        composeRule.awaitText(supermoon.title)
        composeRule.onNodeWithText(supermoon.title).assertIsDisplayed()
    }

    /**
     * A tap arriving while the app is already on screen has to re-route the
     * Activity the user is looking at, not leave them on the screen they were
     * already looking at. Delivered through [MainActivity.deliverNotificationTap],
     * the entry point `onNewIntent` uses: sending the real PendingIntent
     * instead starts the Activity behind ActivityScenario's back, and the
     * scenario can then no longer take it to DESTROYED (`Activity never
     * becomes requested state "[DESTROYED]"`) -- a teardown failure that says
     * nothing about the app.
     */
    @Test
    fun tappingAReminderWhileTheAppIsOpenRoutesTheRunningAppToTheEvent() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        composeRule.awaitText("Upcoming")

        scenario!!.onActivity { it.deliverNotificationTap(openEventIntent(supermoon.id)) }

        composeRule.awaitText(supermoon.title)
    }

    /**
     * A tap can barely reach an unfinished onboarding -- there are no
     * reminders to fire before it -- and if one does, it must not jump the
     * user out of the welcome flow: `finish()` writes the onboarding flag
     * before it runs the sources and re-plans, and navigates to Upcoming only
     * when that returns, so a detail screen opened in between would show
     * mid-setup and be buried moments later.
     */
    @Test
    fun aTapDuringOnboardingLeavesTheUserInOnboarding() {
        runBlocking { context.container.settingsRepo.setOnboardingDone(false) }

        scenario = ActivityScenario.launch(openEventIntent(supermoon.id))

        composeRule.awaitText("Welcome to Skyward")
        assertStaysOffTheDetailScreen("a tap during onboarding")
    }

    /**
     * A notification outlives the row it was posted for: §6.3 drops a
     * withdrawn FORECAST occurrence at the next fetch, while the reminder
     * sits in the shade until someone swipes it. Tapping that stale reminder
     * has to open the app, not a detail screen with nothing behind it -- which
     * renders "Loading…" and never resolves, because the occurrence it is
     * waiting for is never coming back.
     */
    @Test
    fun tappingAReminderForAWithdrawnOccurrenceOpensTheAppInstead() {
        runBlocking { context.container.occurrenceRepo.deleteById(supermoon.id) }

        scenario = ActivityScenario.launch(openEventIntent(supermoon.id))

        composeRule.awaitText("Upcoming")
        assertStaysOffTheDetailScreen("a tap for a withdrawn occurrence")
    }

    /**
     * §6.4's natural keys are not all URL-safe -- a JPL designation carries a
     * slash and a space -- and an unencoded id would split the detail route
     * into path segments no destination matches, so the tap would throw
     * instead of opening anything.
     */
    @Test
    fun tappingAReminderForACometOpensItDespiteTheSlashInItsId() {
        scenario = ActivityScenario.launch(openEventIntent(comet.id))

        composeRule.awaitText(comet.title)
        composeRule.onNodeWithText(comet.title).assertIsDisplayed()
    }

    @Test
    fun anOrdinaryLaunchStillStartsOnUpcoming() {
        scenario = ActivityScenario.launch(MainActivity::class.java)

        composeRule.awaitText("Upcoming")
        assertStaysOffTheDetailScreen("an ordinary launch")
    }

    /**
     * "Nothing happens" needs a window rather than one sample: the routing
     * decision reads the database first, so a single assertion could pass
     * simply by running before it. Watches for the event detail screen's back
     * arrow, which Upcoming and onboarding both lack -- a surer signal than
     * the absence of the seeded titles, which Upcoming may legitimately list.
     */
    private fun assertStaysOffTheDetailScreen(what: String) {
        val deadline = SystemClock.uptimeMillis() + SETTLE_MILLIS
        do {
            composeRule.waitForIdle()
            assertTrue(
                "$what opened a detail screen",
                composeRule.onAllNodesWithContentDescription("Back").fetchSemanticsNodes().isEmpty(),
            )
        } while (SystemClock.uptimeMillis() < deadline)
    }

    private fun openEventIntent(occurrenceId: String) =
        Intent(context, MainActivity::class.java).setAction(openEventAction(occurrenceId))

    private companion object {
        const val SETTLE_MILLIS = 1_000L

        val NOW: Instant = Instant.parse("2026-08-21T00:00:00Z")

        val supermoon = Occurrence(
            id = "sm:2026-11-24",
            phenomenon = Phenomenon.MOON_EVENT,
            sourceId = "moon",
            title = "Supermoon on 24 November",
            window = TimeWindow(NOW + 90.days, NOW + 91.days),
            peakTime = NOW + 90.days + 12.hours,
            certainty = Certainty.CERTAIN,
            payload = MoonEventPayload(
                kind = MoonEventKind.SUPERMOON,
                fullMoonTime = NOW + 90.days + 12.hours,
                perigeeTime = NOW + 90.days + 9.hours,
                perigeeDistanceKm = 356_800.0,
            ),
            fetchedAt = NOW,
            expiresAt = null,
        )

        val comet = Occurrence(
            id = "comet:C/2025 A6",
            phenomenon = Phenomenon.COMET,
            sourceId = "jpl",
            title = "Comet C/2025 A6 (Lemmon)",
            window = TimeWindow(NOW + 10.days, NOW + 40.days),
            peakTime = NOW + 25.days,
            certainty = Certainty.FORECAST,
            payload = CometPayload(
                designation = "C/2025 A6",
                name = "Lemmon",
                elements = CometElements(
                    epoch = NOW,
                    eccentricity = 0.997,
                    perihelionDistanceAu = 0.53,
                    inclinationDeg = 143.7,
                    ascendingNodeDeg = 210.4,
                    argPerihelionDeg = 128.2,
                    tpPerihelion = NOW + 25.days,
                ),
                magParams = CometMagParams(m1 = 8.5, k1 = 10.0),
                perihelionDate = NOW + 25.days,
                peakMag = 4.2,
                peakMagDate = NOW + 25.days,
                magAtIngest = 7.1,
            ),
            fetchedAt = NOW,
            expiresAt = NOW + 30.days,
        )
    }
}
