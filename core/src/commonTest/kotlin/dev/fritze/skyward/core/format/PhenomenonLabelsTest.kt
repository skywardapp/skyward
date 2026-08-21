package dev.fritze.skyward.core.format

import dev.fritze.skyward.core.model.Phenomenon
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PhenomenonLabelsTest {
    /**
     * §4.1: the label set is shared, so a new [Phenomenon] must not reach the
     * UI as a blank chip. The `when` in [phenomenonLabel] already makes that a
     * compile error; this catches the other half — a label added as `""` or as
     * a copy of a neighbour's.
     */
    @Test
    fun `every phenomenon has a distinct, non-blank label`() {
        val labels = Phenomenon.entries.map { phenomenonLabel(it) }
        assertTrue(labels.none { it.isBlank() }, "blank label in $labels")
        assertEquals(Phenomenon.entries.size, labels.toSet().size, "duplicate label in $labels")
    }

    /**
     * The two frontends used to hold a copy each; these are the strings both
     * were rendering when the copies were merged, pinned so the merge can't
     * quietly become a rename.
     */
    @Test
    fun `labels match the strings both frontends shipped`() {
        assertEquals("Solar eclipse", phenomenonLabel(Phenomenon.SOLAR_ECLIPSE))
        assertEquals("Lunar eclipse", phenomenonLabel(Phenomenon.LUNAR_ECLIPSE))
        assertEquals("Aurora", phenomenonLabel(Phenomenon.AURORA))
        assertEquals("Meteor shower", phenomenonLabel(Phenomenon.METEOR_SHOWER))
        assertEquals("Comet", phenomenonLabel(Phenomenon.COMET))
        assertEquals("Supermoon", phenomenonLabel(Phenomenon.MOON_EVENT))
        assertEquals("Conjunction", phenomenonLabel(Phenomenon.CONJUNCTION))
        assertEquals("Earth event", phenomenonLabel(Phenomenon.TERRESTRIAL))
    }
}
