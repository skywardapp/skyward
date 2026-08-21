package dev.fritze.skyward.core.format

import kotlin.test.Test
import kotlin.test.assertEquals

class SourceLabelsTest {
    @Test
    fun `names the sources both frontends shipped`() {
        assertEquals("Aurora (NOAA SWPC)", sourceDisplayName("swpc"))
        assertEquals("Comets (JPL)", sourceDisplayName("jpl"))
        assertEquals("Terrestrial events (NASA EONET)", sourceDisplayName("eonet"))
        assertEquals("Eclipses", sourceDisplayName("eclipse"))
        assertEquals("Meteor showers", sourceDisplayName("meteors"))
        assertEquals("Moon events", sourceDisplayName("moon"))
        assertEquals("Conjunctions", sourceDisplayName("conjunctions"))
    }

    /** An unnamed source has to stay visible in Settings, id and all. */
    @Test
    fun `falls back to the id`() {
        assertEquals("something-new", sourceDisplayName("something-new"))
    }
}
