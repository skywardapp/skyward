package dev.fritze.skyward.core.sources

import dev.fritze.skyward.core.model.Certainty
import dev.fritze.skyward.core.model.GeoPoint
import dev.fritze.skyward.core.model.SavedLocation
import dev.fritze.skyward.core.model.TerrestrialPayload
import dev.fritze.skyward.core.model.TimeWindow
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

class EonetSourceTest {

    private val now = Instant.parse("2026-01-01T00:00:00Z")

    @Test
    fun mapsAnOpenEventIntoATerrestrialOccurrence() = runTest {
        val json = """
            {"events":[
              {"id":"EONET_4242","title":"Some wildfire","link":"https://eonet.gsfc.nasa.gov/api/v3/events/EONET_4242",
               "categories":[{"id":"wildfires","title":"Wildfires"}],
               "geometry":[
                 {"date":"2025-12-30T00:00:00Z","type":"Point","coordinates":[-121.0,38.0]},
                 {"date":"2026-01-01T00:00:00Z","type":"Point","coordinates":[-120.5,38.2],"magnitudeValue":2000.0,"magnitudeUnit":"acres"}
               ]}
            ]}
        """.trimIndent()

        val result = EonetSource(mockClient(json)).refresh(refreshRequest())

        assertEquals(1, result.occurrences.size)
        val occ = result.occurrences.single()
        assertEquals("eonet:EONET_4242", occ.id)
        assertEquals(Certainty.FORECAST, occ.certainty)
        assertEquals(null, occ.peakTime)
        assertEquals(now + 3.days, occ.expiresAt)
        val payload = occ.payload as TerrestrialPayload
        assertEquals("wildfires", payload.categoryId)
        assertEquals(false, payload.closed)
        assertEquals(2000.0, payload.magnitudeValue)
        // Open event: window extends 7 days past fetch time (no closed date yet).
        assertEquals(now + 7.days, occ.window.end)
    }

    @Test
    fun aClosedEventsWindowEndsAtItsClosedDate() = runTest {
        val json = """
            {"events":[
              {"id":"EONET_1","title":"x","link":"x","closed":"2025-12-31T00:00:00Z",
               "categories":[{"id":"volcanoes","title":"Volcanoes"}],
               "geometry":[{"date":"2025-12-20T00:00:00Z","type":"Point","coordinates":[10,20]}]}
            ]}
        """.trimIndent()

        val result = EonetSource(mockClient(json)).refresh(refreshRequest())

        val payload = result.occurrences.single().payload as TerrestrialPayload
        assertTrue(payload.closed)
        assertEquals(Instant.parse("2025-12-31T00:00:00Z"), result.occurrences.single().window.end)
    }

    @Test
    fun defaultCategoriesAreUsedWhenNoSettingIsPresent() = runTest {
        var requestedUrl: Url? = null
        val client = urlCapturingClient { requestedUrl = it }

        EonetSource(client).refresh(refreshRequest())

        val categories = assertNotNull(assertNotNull(requestedUrl).parameters["category"])
        assertTrue(categories.contains("volcanoes"))
        assertTrue(categories.contains("wildfires"))
    }

    @Test
    fun clusteredSavedLocationsNarrowTheRequestWithABbox() = runTest {
        var requestedUrl: Url? = null
        val client = urlCapturingClient { requestedUrl = it }
        val locations = listOf(savedLocation(0, 52.52, 13.405), savedLocation(1, 53.55, 9.99))

        EonetSource(client).refresh(refreshRequest(locations = locations, maxTravelKm = 500.0))

        val expected = assertNotNull(eonetBbox(locations, maxTravelKm = 500.0))
        assertEquals(expected.toQueryValue(), assertNotNull(requestedUrl).parameters["bbox"])
    }

    @Test
    fun scatteredSavedLocationsLeaveTheRequestUnnarrowed() = runTest {
        var requestedUrl: Url? = null
        val client = urlCapturingClient { requestedUrl = it }

        EonetSource(client).refresh(
            refreshRequest(
                locations = listOf(savedLocation(0, 52.52, 13.405), savedLocation(1, -33.87, 151.21)),
                maxTravelKm = 500.0,
            ),
        )

        assertEquals(null, assertNotNull(requestedUrl).parameters["bbox"], "a globe-spanning bbox saves nothing")
    }

    private fun urlCapturingClient(record: (Url) -> Unit): HttpClient {
        val engine = MockEngine { request ->
            record(request.url)
            respond("""{"events":[]}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        return HttpClient(engine) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
    }

    private fun savedLocation(index: Int, latDeg: Double, lonDeg: Double) = SavedLocation(
        id = "loc-$index",
        name = "Location $index",
        point = GeoPoint(latDeg, lonDeg),
        isPrimary = index == 0,
        createdAt = now,
        modifiedAt = now,
    )

    private fun mockClient(json: String): HttpClient {
        val engine = MockEngine { respond(json, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) }
        return HttpClient(engine) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
    }

    private fun refreshRequest(
        locations: List<SavedLocation> = emptyList(),
        maxTravelKm: Double? = null,
    ) = RefreshRequest(
        now = now,
        horizon = TimeWindow(now, now + 365.days),
        locations = locations,
        state = emptyMap(),
        settings = SourceSettings(),
        derivedThresholds = DerivedThresholds(minKpOfInterest = null, maxCometMag = null, maxTravelKm = maxTravelKm),
    )
}
