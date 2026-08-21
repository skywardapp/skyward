package dev.fritze.skyward.core.sources

import dev.fritze.skyward.core.testing.Fixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * §17.3: the EONET parser against a real captured response. Regenerate with
 * `tools/fixtures/fetch-eonet.sh`.
 *
 * Assertions are about shape and invariants rather than the events in the
 * current capture, which are whatever was burning or erupting on the day it
 * was taken.
 */
class EonetFixtureTest {

    @Test
    fun theCapturedResponseParsesIntoUsableEvents() {
        val events = parseEonetEvents(Fixtures.text("eonet_events_open.json"))

        assertTrue(events.size >= 20, "expected a substantial open-events capture, parsed ${events.size}")
        assertEquals(events.map { it.eonetId }.distinct().size, events.size, "event ids should be unique")

        for (event in events) {
            assertTrue(event.eonetId.isNotBlank(), "an event came back with no id")
            assertTrue(event.categoryId.isNotBlank(), "${event.eonetId} has no category id")
            assertTrue(event.categoryTitle.isNotBlank(), "${event.eonetId} has no category title")
            assertTrue(
                event.latestGeometry.latDeg in -90.0..90.0,
                "${event.eonetId}: latitude ${event.latestGeometry.latDeg} out of range -- lon/lat probably swapped",
            )
            assertTrue(event.latestGeometry.lonDeg in -180.0..180.0, "${event.eonetId}: longitude out of range")
            assertTrue(
                event.latestGeometryDate >= event.firstGeometryDate,
                "${event.eonetId}: the latest geometry predates the first one",
            )
        }
    }

    @Test
    fun theRequestedCategoriesAreTheOnesThatComeBack() {
        // The capture is taken with EonetSource's DEFAULT_CATEGORIES; if the
        // parser were reading the wrong element of `categories`, every event
        // would carry something else.
        val categories = parseEonetEvents(Fixtures.text("eonet_events_open.json")).map { it.categoryId }.toSet()
        assertTrue(
            categories.all { it in setOf("volcanoes", "severeStorms", "wildfires") },
            "unexpected categories in a filtered capture: $categories",
        )
    }

    @Test
    fun anOpenEventsCaptureHasNoClosedDates() {
        // status=open is part of the captured query, so a parsed `closed`
        // would mean the field is being read off the wrong event.
        val closed = parseEonetEvents(Fixtures.text("eonet_events_open.json")).filter { it.closed != null }
        assertTrue(closed.isEmpty(), "open-events capture contained closed events: ${closed.map { it.eonetId }}")
    }
}
