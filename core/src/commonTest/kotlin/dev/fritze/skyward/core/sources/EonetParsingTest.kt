package dev.fritze.skyward.core.sources

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

/**
 * The EONET parser's edge cases: geometry kinds, centroids, closed events,
 * and the per-event skips §19 R3 requires. Every input here is *synthetic* --
 * a polygon geometry with a hand-checkable centroid, an event missing its id
 * -- because a real capture cannot be relied on to contain any of them.
 *
 * §17.3's golden test over a real captured response is `desktopTest`'s
 * `EonetFixtureTest`, reading the file `tools/fixtures/fetch-eonet.sh`
 * writes. See `docs/adr/0009-fixture-files-and-jvm-only-golden-tests.md`.
 */
class EonetParsingTest {

    @Test
    fun parsesAPointGeometryEventUsingItsLastGeometryEntry() {
        val events = parseEonetEvents(POINT_EVENT_SAMPLE)
        assertEquals(1, events.size)
        val event = events[0]
        assertEquals("EONET_1234", event.eonetId)
        assertEquals("wildfires", event.categoryId)
        assertEquals("Wildfires", event.categoryTitle)
        assertEquals(Instant.parse("2026-08-01T00:00:00Z"), event.firstGeometryDate)
        assertEquals(Instant.parse("2026-08-10T00:00:00Z"), event.latestGeometryDate)
        assertEquals(-120.5, event.latestGeometry.lonDeg)
        assertEquals(38.2, event.latestGeometry.latDeg)
        assertEquals(1500.0, event.magnitudeValue)
        assertEquals("acres", event.magnitudeUnit)
        assertNull(event.closed)
    }

    @Test
    fun polygonGeometryUsesTheArithmeticCentroidOfTheOuterRing() {
        val events = parseEonetEvents(POLYGON_EVENT_SAMPLE)
        assertEquals(1, events.size)
        val point = events[0].latestGeometry
        // Square ring (0,0)-(0,2)-(2,2)-(2,0) -> centroid (1,1) [lon,lat].
        assertEquals(1.0, point.lonDeg)
        assertEquals(1.0, point.latDeg)
    }

    @Test
    fun closedEventParsesTheClosedDate() {
        val events = parseEonetEvents(CLOSED_EVENT_SAMPLE)
        assertEquals(Instant.parse("2026-08-05T00:00:00Z"), events[0].closed)
    }

    @Test
    fun eventsWithNoCategoryOrNoGeometryAreSkipped() {
        val sample = """
            {"events":[
              {"id":"EONET_1","title":"No category","link":"x","categories":[],"geometry":[{"date":"2026-08-01T00:00:00Z","type":"Point","coordinates":[1,2]}]},
              {"id":"EONET_2","title":"No geometry","link":"x","categories":[{"id":"volcanoes","title":"Volcanoes"}],"geometry":[]}
            ]}
        """.trimIndent()
        assertEquals(0, parseEonetEvents(sample).size)
    }

    @Test
    fun emptyEventsListProducesNoEvents() {
        assertEquals(0, parseEonetEvents("""{"events":[]}""").size)
    }

    @Test
    fun oneEventMissingARequiredFieldDoesNotDiscardTheOtherValidEvents() {
        // §19 R3: one malformed element in the array must not fail the whole
        // response -- each event is decoded independently.
        val sample = """
            {"events":[
              {"id":"EONET_GOOD","title":"Valid event","link":"x",
               "categories":[{"id":"wildfires","title":"Wildfires"}],
               "geometry":[{"date":"2026-08-01T00:00:00Z","type":"Point","coordinates":[1,2]}]},
              {"title":"Missing id field entirely",
               "categories":[{"id":"wildfires","title":"Wildfires"}],
               "geometry":[{"date":"2026-08-01T00:00:00Z","type":"Point","coordinates":[1,2]}]}
            ]}
        """.trimIndent()

        val events = parseEonetEvents(sample)

        assertEquals(1, events.size)
        assertEquals("EONET_GOOD", events[0].eonetId)
    }

    private companion object {
        val POINT_EVENT_SAMPLE = """
            {"title":"EONET Events","events":[
              {"id":"EONET_1234","title":"Wildfire near somewhere","link":"https://eonet.gsfc.nasa.gov/api/v3/events/EONET_1234",
               "categories":[{"id":"wildfires","title":"Wildfires"}],
               "geometry":[
                 {"date":"2026-08-01T00:00:00Z","type":"Point","coordinates":[-121.0,38.0]},
                 {"date":"2026-08-10T00:00:00Z","type":"Point","coordinates":[-120.5,38.2],"magnitudeValue":1500.0,"magnitudeUnit":"acres"}
               ]}
            ]}
        """.trimIndent()

        val POLYGON_EVENT_SAMPLE = """
            {"events":[
              {"id":"EONET_5678","title":"Flood","link":"x",
               "categories":[{"id":"floods","title":"Floods"}],
               "geometry":[
                 {"date":"2026-08-01T00:00:00Z","type":"Polygon","coordinates":[[[0,0],[0,2],[2,2],[2,0],[0,0]]]}
               ]}
            ]}
        """.trimIndent()

        val CLOSED_EVENT_SAMPLE = """
            {"events":[
              {"id":"EONET_9999","title":"Storm","link":"x","closed":"2026-08-05T00:00:00Z",
               "categories":[{"id":"severeStorms","title":"Severe Storms"}],
               "geometry":[{"date":"2026-08-01T00:00:00Z","type":"Point","coordinates":[10,20]}]}
            ]}
        """.trimIndent()
    }
}
