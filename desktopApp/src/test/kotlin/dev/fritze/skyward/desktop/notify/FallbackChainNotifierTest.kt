package dev.fritze.skyward.desktop.notify

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** §10.3's notification fallback chain: two-slices, then `notify-send`. */
class FallbackChainNotifierTest {

    private class RecordingBackend(var deliver: Boolean) : DesktopNotifier {
        var attempts = 0

        override fun post(notification: DesktopNotification, onActivated: (String?) -> Unit): Boolean {
            attempts++
            return deliver
        }
    }

    private fun post(notifier: DesktopNotifier) =
        notifier.post(DesktopNotification("t", "b", occurrenceId = null), onActivated = {})

    @Test
    fun fallsThroughToTheNextBackend() {
        val broken = RecordingBackend(deliver = false)
        val working = RecordingBackend(deliver = true)

        assertTrue(post(FallbackChainNotifier(listOf(broken, working))))
        assertEquals(1, broken.attempts)
        assertEquals(1, working.attempts)
    }

    @Test
    fun aWorkingBackendIsRememberedRatherThanRediscovered() {
        val broken = RecordingBackend(deliver = false)
        val working = RecordingBackend(deliver = true)
        val notifier = FallbackChainNotifier(listOf(broken, working))

        repeat(3) { assertTrue(post(notifier)) }

        // The broken first choice costs one failed attempt per process, not one per reminder.
        assertEquals(1, broken.attempts)
        assertEquals(3, working.attempts)
    }

    @Test
    fun theRememberedBackendGoingAwayDoesNotLoseTheReminder() {
        // The notification daemon restarting mid-session is the real case:
        // remembering a backend must be a preference, not a commitment, or a
        // reminder silently vanishes — the failure this chain exists to stop.
        val first = RecordingBackend(deliver = true)
        val second = RecordingBackend(deliver = false)
        val notifier = FallbackChainNotifier(listOf(first, second))

        assertTrue(post(notifier))

        first.deliver = false
        second.deliver = true
        assertTrue(post(notifier))
        assertEquals(2, first.attempts) // the remembered one, tried and failed
        assertEquals(1, second.attempts)

        // ...and the new choice sticks, without re-trying the one that broke.
        assertTrue(post(notifier))
        assertEquals(2, first.attempts)
    }

    @Test
    fun aChainWithNothingWorkingReportsFailureOnce() {
        val a = RecordingBackend(deliver = false)
        val b = RecordingBackend(deliver = false)

        assertFalse(post(FallbackChainNotifier(listOf(a, b))))
        assertEquals(1, a.attempts)
        assertEquals(1, b.attempts)
    }
}
