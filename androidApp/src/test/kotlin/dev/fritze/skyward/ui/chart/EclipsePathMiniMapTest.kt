package dev.fritze.skyward.ui.chart

import dev.fritze.skyward.core.chart.EclipsePathPolyline
import dev.fritze.skyward.core.model.GeoPoint
import dev.fritze.skyward.core.model.SavedLocation
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * §13.3's mini-map announces the latitude band its track crosses — the one
 * fact the drawing carries that the times table above it does not.
 *
 * A JVM test rather than a Compose one: the string is pure, and §17.5's
 * instrumented tests need an emulator and run from their own workflow.
 */
class EclipsePathMiniMapTest {

    private fun polyline(vararg points: GeoPoint) = EclipsePathPolyline(
        occurrenceId = "se:test",
        title = "Total solar eclipse",
        peakTime = kotlin.time.Instant.parse("2027-08-02T10:00:00Z"),
        segments = listOf(points.toList()),
    )

    private fun location(id: String, name: String) = SavedLocation(
        id = id,
        name = name,
        point = GeoPoint(52.0, 13.0),
        isPrimary = id == "a",
        createdAt = kotlin.time.Instant.parse("2026-01-01T00:00:00Z"),
        modifiedAt = kotlin.time.Instant.parse("2026-01-01T00:00:00Z"),
    )

    @Test
    fun theBandIsRoundedOutwardSoItNeverUnderstatesTheTrack() {
        val description = buildContentDescription(
            polyline(GeoPoint(-0.7, 10.0), GeoPoint(20.7, 30.0)),
            emptyList(),
        )
        // Truncating toward zero would say "between 0 and 20", which is both
        // narrower than the truth and on the wrong side of the equator.
        assertTrue("between latitudes -1 and 21 degrees" in description, description)
    }

    @Test
    fun savedLocationsAreCountedForScreenReaders() {
        val locations = listOf(location("a", "Home"), location("b", "Cabin"))
        assertTrue("2 saved location markers" in buildContentDescription(polyline(GeoPoint(10.0, 10.0)), locations))
    }

    @Test
    fun withNoSavedLocationsTheMarkerCountIsOmittedRatherThanZero() {
        val description = buildContentDescription(polyline(GeoPoint(10.0, 10.0)), emptyList())
        assertTrue("saved location" !in description, description)
    }
}
