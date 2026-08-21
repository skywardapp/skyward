package dev.fritze.skyward.alarm

import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
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
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
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
 * launching intent is the thing under test.
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
            // A reminder can only fire after onboarding, and the tap is
            // deliberately held until it is done -- so this is the state the
            // feature actually runs in.
            context.container.settingsRepo.setOnboardingDone(true)
            context.container.occurrenceRepo.upsert(supermoon, NOW)
            context.container.occurrenceRepo.upsert(comet, NOW)
        }
    }

    @After
    fun closeActivity() {
        scenario?.close()
    }

    @Test
    fun tappingAReminderOnAClosedAppOpensItsEvent() {
        scenario = ActivityScenario.launch(openEventIntent(supermoon.id))

        composeRule.awaitText(supermoon.title)
        composeRule.onNodeWithText(supermoon.title).assertIsDisplayed()
    }

    /**
     * The `singleTop` half of §10.2's tap target: a tap arriving while the app
     * is already on screen has to re-route the Activity the user is looking at
     * (via `onNewIntent`), not stack a second copy of the whole app on top of
     * it -- which is what the same intent does without the manifest's
     * launchMode, and which leaves a Back press on a duplicate app rather than
     * on Upcoming.
     */
    @Test
    fun tappingAReminderWhileTheAppIsOpenRoutesTheRunningAppToTheEvent() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        composeRule.awaitText("Upcoming")

        // send(), not startActivity(): this is exactly what the notification
        // shade does with the PendingIntent NotificationPoster attaches.
        openEventPendingIntent(context, supermoon.id).send()

        composeRule.awaitText(supermoon.title)
        assertEquals("expected one MainActivity, not a stacked second copy", 1, liveMainActivities())
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
        composeRule.waitForIdle()
        // Upcoming is a top-level destination and has no back arrow; the event
        // detail screen does. Asserted on that rather than on the absence of
        // the seeded titles, which Upcoming may legitimately list.
        assertTrue(
            "an ordinary launch opened a detail screen",
            composeRule.onAllNodesWithContentDescription("Back").fetchSemanticsNodes().isEmpty(),
        )
    }

    private fun openEventIntent(occurrenceId: String) =
        Intent(context, MainActivity::class.java).setAction(openEventAction(occurrenceId))

    /** MainActivity instances the runtime still holds, destroyed ones aside. */
    private fun liveMainActivities(): Int {
        var count = 0
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val monitor = ActivityLifecycleMonitorRegistry.getInstance()
            count = Stage.values()
                .filter { it != Stage.DESTROYED }
                .flatMap { stage -> monitor.getActivitiesInStage(stage).filterIsInstance<MainActivity>() }
                .distinct()
                .size
        }
        return count
    }

    private companion object {
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

/**
 * The detail screen only names its occurrence once the DB read behind it has
 * emitted -- a read Compose's idling resource knows nothing about -- so
 * `waitForIdle()` alone can return with "Loading…" still on screen.
 */
private fun ComposeTestRule.awaitText(text: String) {
    waitUntil(timeoutMillis = TEXT_TIMEOUT_MILLIS) {
        onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }
}

private const val TEXT_TIMEOUT_MILLIS = 10_000L
