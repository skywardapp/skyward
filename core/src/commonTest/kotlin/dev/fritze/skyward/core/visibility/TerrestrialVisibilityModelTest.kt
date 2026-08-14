package dev.fritze.skyward.core.visibility

import dev.fritze.skyward.core.model.Certainty
import dev.fritze.skyward.core.model.GeoPoint
import dev.fritze.skyward.core.model.Occurrence
import dev.fritze.skyward.core.model.Phenomenon
import dev.fritze.skyward.core.model.Quality
import dev.fritze.skyward.core.model.SavedLocation
import dev.fritze.skyward.core.model.TerrestrialPayload
import dev.fritze.skyward.core.model.TimeWindow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.time.Instant

class TerrestrialVisibilityModelTest {

    private val model = TerrestrialVisibilityModel()

    @Test
    fun neverClaimsLocalVisibilityAndAlwaysReportsRawDistanceForReachableWithinToThreshold() {
        val eventPoint = GeoPoint(46.2, 12.9) // somewhere in the Alps
        val loc = GeoPoint(48.1351, 11.5820) // Munich
        val now = Instant.parse("2026-01-01T00:00:00Z")

        val occ = Occurrence(
            id = "eonet:1",
            phenomenon = Phenomenon.TERRESTRIAL,
            sourceId = "eonet",
            title = "Wildfire",
            window = TimeWindow(now, now),
            peakTime = null,
            certainty = Certainty.FORECAST,
            payload = TerrestrialPayload(
                eonetId = "EONET_1",
                categoryId = "wildfires",
                categoryTitle = "Wildfires",
                latestGeometry = eventPoint,
                geometryDate = now,
                magnitudeValue = null,
                magnitudeUnit = null,
                link = "https://eonet.gsfc.nasa.gov/api/v3/events/EONET_1",
                closed = false,
            ),
            fetchedAt = now,
            expiresAt = null,
        )

        val result = model.evaluate(
            occ,
            SavedLocation(id = "loc", name = "Munich", point = loc, isPrimary = true, createdAt = now, modifiedAt = now),
            VisibilityContext(now = now, ovationGrid = null),
        )

        assertFalse(result.visibleAtLocation)
        assertEquals(Quality.GOOD, result.quality)
        assertEquals(Quality.GOOD, result.qualityAtNearestPoint)
        assertEquals(eventPoint, result.nearestVisiblePoint)
        val expectedDistanceKm = haversineDistanceKm(loc, eventPoint)
        assertEquals(expectedDistanceKm, result.travelDistanceKm)
    }
}
