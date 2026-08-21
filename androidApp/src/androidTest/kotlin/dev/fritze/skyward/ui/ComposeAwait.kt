package dev.fritze.skyward.ui

import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithText

/**
 * Compose's idling resource settles the *frame*, not the coroutine that
 * decides what to draw: the permission cards in Upcoming are toggled from a
 * `LifecycleEventEffect` that reads the settings DB, so asserting straight
 * after `setContent` is a race. Shared by the card tests so they can't drift
 * into two different definitions of "settled".
 */
internal fun ComposeTestRule.awaitText(text: String) {
    waitUntil(timeoutMillis = AWAIT_TIMEOUT_MILLIS) {
        onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }
}

internal fun ComposeTestRule.awaitTextGone(text: String) {
    waitUntil(timeoutMillis = AWAIT_TIMEOUT_MILLIS) {
        onAllNodesWithText(text).fetchSemanticsNodes().isEmpty()
    }
}

private const val AWAIT_TIMEOUT_MILLIS = 10_000L
