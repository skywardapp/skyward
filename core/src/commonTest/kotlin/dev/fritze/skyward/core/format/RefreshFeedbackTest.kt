package dev.fritze.skyward.core.format

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RefreshFeedbackTest {
    @Test
    fun `says nothing when nothing failed`() {
        assertNull(refreshFailureMessage(emptyList()))
    }

    @Test
    fun `names a single failed source`() {
        assertEquals(
            "Couldn't reach Aurora (NOAA SWPC) — see Settings > Sources",
            refreshFailureMessage(listOf("swpc")),
        )
    }

    @Test
    fun `names both when two failed`() {
        assertEquals(
            "Couldn't reach Aurora (NOAA SWPC) and Comets (JPL) — see Settings > Sources",
            refreshFailureMessage(listOf("swpc", "jpl")),
        )
    }

    /** Three or more: one name plus a count, so the line stays one line. */
    @Test
    fun `counts the rest beyond two`() {
        assertEquals(
            "Couldn't reach Aurora (NOAA SWPC) and 2 more sources — see Settings > Sources",
            refreshFailureMessage(listOf("swpc", "jpl", "eonet")),
        )
    }

    @Test
    fun `keeps the order it was given`() {
        assertEquals(
            "Couldn't reach Comets (JPL) and Aurora (NOAA SWPC) — see Settings > Sources",
            refreshFailureMessage(listOf("jpl", "swpc")),
        )
    }
}
