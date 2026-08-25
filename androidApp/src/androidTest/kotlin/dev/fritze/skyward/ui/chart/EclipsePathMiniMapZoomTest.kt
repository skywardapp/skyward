package dev.fritze.skyward.ui.chart

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.fritze.skyward.core.model.Certainty
import dev.fritze.skyward.core.model.GeoPoint
import dev.fritze.skyward.core.model.Occurrence
import dev.fritze.skyward.core.model.PathSample
import dev.fritze.skyward.core.model.Phenomenon
import dev.fritze.skyward.core.model.SolarEclipseKind
import dev.fritze.skyward.core.model.SolarEclipsePayload
import dev.fritze.skyward.core.model.TimeWindow
import dev.fritze.skyward.ui.awaitText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant
import org.junit.Rule
import org.junit.runner.RunWith

/**
 * ADR 0026's two halves, which are one decision: §13.3's mini-map takes
 * two-finger gestures, and hands one-finger drags back to the list it sits
 * in. Neither is checkable without a real pointer stream, so this is a §17.5
 * instrumented test and not a JVM one.
 *
 * The map is placed inside a `LazyColumn` here because that is what
 * EventDetail does. Asserting the scroll half against a bare `setContent`
 * would pass for the wrong reason — there would be nothing to steal the drag
 * from.
 */
@RunWith(AndroidJUnit4::class)
class EclipsePathMiniMapZoomTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun twoFingersZoomPastTheWholeWorldDefaultAndResetReturnsToIt() {
        composeRule.setContent { DetailLikeList() }
        composeRule.awaitText(TITLE)

        // Nothing to reset, and no factor worth reporting, until a gesture
        // moves the camera off 1×.
        composeRule.onNodeWithText(RESET).assertDoesNotExist()

        composeRule.onNodeWithContentDescription(MAP_DESCRIPTION, substring = true).performTouchInput {
            pinch(
                start0 = center + Offset(-PINCH_START_PX, 0f),
                end0 = center + Offset(-PINCH_END_PX, 0f),
                start1 = center + Offset(PINCH_START_PX, 0f),
                end1 = center + Offset(PINCH_END_PX, 0f),
            )
        }

        // The readout and the way back exist only above 1×, so their arrival
        // is the assertion that the pinch reached the camera.
        composeRule.awaitText(RESET)
        composeRule.onNodeWithText(RESET).assertIsDisplayed()

        composeRule.onNodeWithText(RESET).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(RESET).assertDoesNotExist()
    }

    /**
     * Asserted against the list's own scroll position rather than against
     * which row became visible. How far one swipe travels depends on the
     * canvas height, which depends on the screen width, which differs across
     * the API levels this suite runs on — and a deliberately slow swipe does
     * not fling, so it moves the list by roughly the canvas height and no
     * more. "Some row is now on screen" encodes all of that; "the list
     * scrolled at all" is the actual proposition.
     */
    @Test
    fun oneFingerOnTheMapStillScrollsThePageBehindIt() {
        lateinit var listState: LazyListState
        composeRule.setContent {
            listState = rememberLazyListState()
            DetailLikeList(listState)
        }
        composeRule.awaitText(TITLE)
        composeRule.runOnIdle {
            assertEquals(0, listState.firstVisibleItemIndex)
            assertEquals(0, listState.firstVisibleItemScrollOffset)
        }

        // Dragged from inside the map, not from a row beside it: a detector
        // that claimed every drag would swallow exactly this gesture and
        // strand the reader on a screen that will not scroll.
        composeRule.onNodeWithContentDescription(MAP_DESCRIPTION, substring = true)
            .performTouchInput { swipeUp(durationMillis = SLOW_SWIPE_MILLIS) }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            val scrolled = listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0
            assertTrue(scrolled, "the one-finger drag never reached the list behind the canvas")
        }

        // …and it scrolled rather than zoomed. Back to the top by the list's
        // own semantics action, so returning there is not a second swipe with
        // its own geometry to get wrong.
        composeRule.onNode(hasScrollAction()).performScrollToIndex(0)
        composeRule.awaitText(TITLE)
        composeRule.onNodeWithText(RESET).assertDoesNotExist()
    }

    @Composable
    private fun DetailLikeList(listState: LazyListState = rememberLazyListState()) {
        LazyColumn(Modifier.fillMaxWidth(), state = listState) {
            item { EclipsePathMiniMap(TOTAL_ECLIPSE, locations = emptyList()) }
            items(FILLER_ROWS) { label -> Text(label, Modifier.height(ROW_HEIGHT)) }
        }
    }

    private companion object {
        const val TITLE = "Central eclipse path"
        const val RESET = "Reset"
        const val MAP_DESCRIPTION = "Map of the eclipse central path"

        // Slow enough not to fling: a fling keeps scrolling after the gesture
        // ends, which makes "did the list move" a timing question.
        const val SLOW_SWIPE_MILLIS = 1000L

        // Fingers 80 px apart spreading to 320 px — a 4× pinch, comfortably
        // clear of MapCamera.MIN_ZOOM however the emulator's density rounds it.
        const val PINCH_START_PX = 40f
        const val PINCH_END_PX = 160f

        val ROW_HEIGHT = 80.dp

        /** Enough rows that the list is scrollable on any test device. */
        val FILLER_ROWS = (0..29).map { "Filler row $it" }

        /**
         * A short synthetic track rather than a fixture: this test is about
         * pointer plumbing, and §17.1's golden GSFC rows are what checks the
         * path geometry.
         */
        val TOTAL_ECLIPSE = Occurrence(
            id = "se:2027-08-02",
            phenomenon = Phenomenon.SOLAR_ECLIPSE,
            sourceId = "eclipse",
            title = "Total solar eclipse",
            window = TimeWindow(
                start = Instant.parse("2027-08-02T07:30:00Z"),
                end = Instant.parse("2027-08-02T12:30:00Z"),
            ),
            peakTime = Instant.parse("2027-08-02T10:07:00Z"),
            certainty = Certainty.CERTAIN,
            payload = SolarEclipsePayload(
                kind = SolarEclipseKind.TOTAL,
                greatestEclipsePoint = GeoPoint(25.5, 33.2),
                greatestEclipseTime = Instant.parse("2027-08-02T10:07:00Z"),
                centralPath = (0..20).map { step ->
                    PathSample(
                        time = Instant.parse("2027-08-02T08:00:00Z"),
                        point = GeoPoint(latDeg = 30.0 - step * 0.4, lonDeg = -10.0 + step * 3.0),
                        pathWidthKm = 258.0,
                        centralDurationSec = 380.0,
                    )
                },
                obscurationAtGreatest = 1.0,
            ),
            fetchedAt = Instant.parse("2026-01-01T00:00:00Z"),
            expiresAt = null,
        )
    }
}
