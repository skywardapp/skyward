package dev.fritze.skyward.alarm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * §10.2's tap target, at the one point of it that is pure string work: what
 * a launch intent's action says about which occurrence to open (#51).
 *
 * The occurrence id rides in the action rather than an extra (see
 * [openEventPendingIntent] for why), so it has to survive a round trip
 * through the characters §6.4's natural keys actually contain -- colons
 * everywhere, and a slash and a space in comet designations.
 */
class NotificationContentIntentsTest {

    @Test
    fun everyNaturalKeyShapeSurvivesTheRoundTrip() {
        val ids = listOf(
            "se:2026-08-12",              // solar eclipse
            "ms:PER:2026",                // meteor shower
            "au:3d:2026-08-12:18-21UT",   // aurora forecast slot
            "comet:C/2025 A6",            // JPL designation: slash and space
            "eonet:EONET_6789",
        )

        for (id in ids) {
            assertEquals(id, occurrenceIdFromLaunchAction(openEventAction(id)), "round trip for $id")
        }
    }

    @Test
    fun anOrdinaryLaunchNamesNoOccurrence() {
        // Launcher icon (ACTION_MAIN), a bare relaunch (null action), and the
        // alarm receiver's own action -- which shares the app's action
        // namespace and must not be mistaken for a tap on a notification.
        assertNull(occurrenceIdFromLaunchAction("android.intent.action.MAIN"))
        assertNull(occurrenceIdFromLaunchAction(null))
        assertNull(occurrenceIdFromLaunchAction(ACTION_PREFIX + "notification-42"))
    }

    @Test
    fun anEmptyIdIsNotAnOccurrence() {
        // Defensive: an id-less OPEN_EVENT action must open the app plainly
        // rather than navigate to a detail screen for "".
        assertNull(occurrenceIdFromLaunchAction(OPEN_EVENT_ACTION_PREFIX))
    }
}
