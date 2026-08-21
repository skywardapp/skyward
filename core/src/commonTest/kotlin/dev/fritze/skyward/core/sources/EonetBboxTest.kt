package dev.fritze.skyward.core.sources

import dev.fritze.skyward.core.model.GeoPoint
import dev.fritze.skyward.core.model.SavedLocation
import dev.fritze.skyward.core.visibility.destinationPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * §7.7 third bullet: the EONET request-narrowing bbox. When it is sent at
 * all is docs/adr/0008-eonet-bbox-narrowing-conditions.md; what it says when
 * it is sent is §7.7's own (nonstandard) axis order.
 */
class EonetBboxTest {

    @Test
    fun axisOrderIsEonetsNonstandardMinLonMaxLatMaxLonMinLat() {
        // Berlin + Hamburg (~255 km apart), padded by a 500 km travel radius.
        val bbox = assertNotNull(eonetBbox(locations(52.52 to 13.405, 53.55 to 9.99), thresholds(500.0)))

        val parts = bbox.toQueryValue().split(",").map { it.toDouble() }
        assertEquals(4, parts.size)
        assertEquals(2.407, parts[0], 0.01, "first component is minLon")
        assertEquals(58.047, parts[1], 0.01, "second component is maxLat")
        assertEquals(20.808, parts[2], 0.01, "third component is maxLon")
        assertEquals(48.023, parts[3], 0.01, "fourth component is minLat")
        // The trap this test exists for: GeoJSON order would put a latitude second.
        assertTrue(parts[0] < parts[2], "longitudes ascend across components 0 and 2")
        assertTrue(parts[1] > parts[3], "latitudes descend across components 1 and 3")
    }

    @Test
    fun theBoxCoversEveryPointWithinTheTravelRadiusOfASavedLocation() {
        val travelKm = 500.0
        val locations = locations(52.52 to 13.405, 53.55 to 9.99)
        val bbox = assertNotNull(eonetBbox(locations, thresholds(travelKm)))

        // Sample the travel circle around each location in 10-degree steps:
        // every point a `ReachableWithin(500 km)` rule could match must be
        // inside the box, or the "optimization" silently drops events.
        for (location in locations) {
            for (bearing in 0 until 360 step 10) {
                val edge = destinationPoint(location.point, travelKm, bearing.toDouble())
                assertTrue(
                    edge.latDeg in bbox.minLat..bbox.maxLat && edge.lonDeg in bbox.minLon..bbox.maxLon,
                    "$edge (bearing $bearing from ${location.name}) is outside $bbox",
                )
            }
        }
    }

    @Test
    fun aSingleSavedLocationIsNotNarrowed() {
        assertNull(eonetBbox(locations(52.52 to 13.405), thresholds(500.0)))
        assertNull(eonetBbox(emptyList(), thresholds(500.0)))
    }

    @Test
    fun locationsFurtherApartThanTheClusterLimitAreNotNarrowed() {
        // Berlin and Sydney: a box covering both is most of the planet.
        assertNull(eonetBbox(locations(52.52 to 13.405, -33.87 to 151.21), thresholds(500.0)))
    }

    @Test
    fun anOutlierBeyondTheClusterLimitSuppressesNarrowingForAllLocations() {
        // Two locations 255 km apart plus one 5 000 km away: §7.7's literal
        // ">= 2 within 2 000 km" holds, but the box would still have to
        // stretch to the outlier, so there is nothing to save.
        assertNull(
            eonetBbox(locations(52.52 to 13.405, 53.55 to 9.99, 25.2 to 55.27), thresholds(500.0)),
        )
    }

    @Test
    fun aRuleThatCanMatchAtAnyDistanceSuppressesNarrowing() {
        // The one way a bbox can cost a match: another rule's travel radius
        // is not a bound on a terrestrial rule that has none of its own
        // (ADR 0008). Also covers "no rule sets a radius at all", which
        // leaves nothing to pad with.
        val locations = locations(52.52 to 13.405, 53.55 to 9.99)

        assertNull(
            eonetBbox(
                locations,
                DerivedThresholds(
                    minKpOfInterest = null,
                    maxCometMag = null,
                    maxTravelKm = 500.0,
                    terrestrialRulesAreTravelBounded = false,
                ),
            ),
        )
        assertNull(
            eonetBbox(
                locations,
                DerivedThresholds(
                    minKpOfInterest = null,
                    maxCometMag = null,
                    maxTravelKm = null,
                    terrestrialRulesAreTravelBounded = true,
                ),
            ),
        )
    }

    @Test
    fun aClusterStraddlingTheAntimeridianKeepsItsLatitudeBandButAllLongitudes() {
        // Taveuni (Fiji) and a point just across 180: a two-corner bbox
        // cannot wrap, so longitude is given up and latitude kept.
        val bbox = assertNotNull(
            eonetBbox(locations(-16.85 to 179.97, -16.6 to -179.8), thresholds(300.0)),
        )

        assertEquals(-180.0, bbox.minLon)
        assertEquals(180.0, bbox.maxLon)
        assertTrue(bbox.minLat > -25.0 && bbox.maxLat < -10.0, "latitude is still narrowed: $bbox")
    }

    @Test
    fun aClusterWhosePaddingSwallowsAPoleTakesAllLongitudes() {
        // Longyearbyen and Ny-Alesund, padded past 90 deg N.
        val bbox = assertNotNull(eonetBbox(locations(78.22 to 15.65, 78.92 to 11.93), thresholds(1500.0)))

        assertEquals(90.0, bbox.maxLat, "latitude clamps at the pole rather than overflowing")
        assertEquals(-180.0, bbox.minLon)
        assertEquals(180.0, bbox.maxLon)
        assertTrue(bbox.minLat > 60.0, "the southern edge is still narrowed: $bbox")
    }

    @Test
    fun coordinatesAreRoundedSoIdenticalInputsProduceIdenticalUrls() {
        val locations = locations(52.5 to 13.4, 53.5 to 10.0)
        val first = assertNotNull(eonetBbox(locations, thresholds(500.0))).toQueryValue()
        val second = assertNotNull(eonetBbox(locations, thresholds(500.0))).toQueryValue()

        assertEquals(first, second)
        for (part in first.split(",")) {
            val decimals = part.substringAfter('.', "").length
            assertTrue(decimals <= 3, "$part in $first carries more precision than EONET positions have")
        }
    }

    /** Thresholds a user whose terrestrial rules all bound travel would derive. */
    private fun thresholds(maxTravelKm: Double) = DerivedThresholds(
        minKpOfInterest = null,
        maxCometMag = null,
        maxTravelKm = maxTravelKm,
        terrestrialRulesAreTravelBounded = true,
    )

    private fun locations(vararg latLon: Pair<Double, Double>): List<SavedLocation> =
        latLon.mapIndexed { index, (lat, lon) ->
            SavedLocation(
                id = "loc-$index",
                name = "Location $index",
                point = GeoPoint(lat, lon),
                isPrimary = index == 0,
                createdAt = Instant.parse("2026-01-01T00:00:00Z"),
                modifiedAt = Instant.parse("2026-01-01T00:00:00Z"),
            )
        }
}
