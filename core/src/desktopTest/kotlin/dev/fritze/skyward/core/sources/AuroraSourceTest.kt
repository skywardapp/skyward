package dev.fritze.skyward.core.sources

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.fritze.skyward.core.model.AuroraForecastKind
import dev.fritze.skyward.core.model.AuroraPayload
import dev.fritze.skyward.core.model.GeoPoint
import dev.fritze.skyward.core.model.SavedLocation
import dev.fritze.skyward.core.model.TimeWindow
import dev.fritze.skyward.core.persistence.SkywardDatabase
import dev.fritze.skyward.core.persistence.SourceStateRepo
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** §17.3/§18: "a simulated high-Kp fixture produces a nowcast notification within one poll cycle" plus the tiered-polling / ingest-prefilter rules of §7.3.2/§7.3.3. */
class AuroraSourceTest {

    private val now = Instant.parse("2026-01-01T00:00:00Z")
    private val home = SavedLocation("loc1", "Home", GeoPoint(latDeg = 60.0, lonDeg = 10.0), isPrimary = true, createdAt = now, modifiedAt = now)

    @Test
    fun lowMaxKpNext48hStaysIdleAndNeverFetchesOvation() = runTest {
        val requestedUrls = mutableListOf<String>()
        val source = AuroraSource(mockClient(requestedUrls, forecast = kpForecastFixture(kp = 2.0), ovation = null))

        val result = source.refresh(refreshRequest(locations = listOf(home), minKp = 5.0))

        assertTrue(requestedUrls.none { it.contains("ovation_aurora_latest") }, "idle tier must never poll OVATION (§7.3.2)")
        assertEquals(now + 3.hours, result.nextRefreshHint)
        assertTrue(result.occurrences.none { (it.payload as AuroraPayload).forecastKind == AuroraForecastKind.NOWCAST })
    }

    @Test
    fun noRuleCaringAboutKpStaysIdleRegardlessOfForecastAndKeepsEveryFutureSlot() = runTest {
        // thresholdKp == null: no enabled rule has a KpAtLeast condition, so
        // there's no basis to compare maxKpNext48h against -- goActive stays
        // false (documented in AuroraSource.kt), and the §7.3.3 THREE_DAY
        // pruning ("below every threshold -> nothing") has no threshold to
        // prune by either, so every future slot survives unfiltered.
        val requestedUrls = mutableListOf<String>()
        val source = AuroraSource(mockClient(requestedUrls, forecast = kpForecastFixture(kp = 9.0), ovation = null))

        val result = source.refresh(refreshRequest(locations = listOf(home), minKp = null))

        assertEquals(now + 3.hours, result.nextRefreshHint)
        assertTrue(requestedUrls.none { it.contains("ovation_aurora_latest") })
        val threeDay = result.occurrences.filter { (it.payload as AuroraPayload).forecastKind == AuroraForecastKind.THREE_DAY }
        assertEquals(1, threeDay.size, "the high-Kp slot must still survive since there's no threshold to prune it by")
    }

    @Test
    fun highMaxKpNext48hGoesActiveAndFetchesOvationInTheSamePollCycle() = runTest {
        val requestedUrls = mutableListOf<String>()
        val ovation = ovationFixture(listOf(Triple(10, 60, 80))) // exactly at `home`'s coordinates
        val source = AuroraSource(mockClient(requestedUrls, forecast = kpForecastFixture(kp = 6.0), ovation = ovation))

        val result = source.refresh(refreshRequest(locations = listOf(home), minKp = 5.0))

        assertTrue(requestedUrls.any { it.contains("ovation_aurora_latest") }, "active tier must poll OVATION")
        assertEquals(now + 15.minutes, result.nextRefreshHint)
    }

    @Test
    fun aSimulatedHighKpFixtureProducesANowcastOccurrenceInTheSamePollCycle() = runTest {
        // The §18 accept criterion, at the source level: no separate "next cycle"
        // needed to flip tiers -- AuroraSource recomputes its tier fresh from
        // the just-fetched forecast and, if active, fetches+evaluates OVATION
        // within this very refresh() call (AuroraSource.kt's own top comment).
        val ovation = ovationFixture(listOf(Triple(10, 60, 80)))
        val source = AuroraSource(mockClient(mutableListOf(), forecast = kpForecastFixture(kp = 7.0), ovation = ovation))

        val result = source.refresh(refreshRequest(locations = listOf(home), minKp = 5.0))

        val nowcast = result.occurrences.singleOrNull { (it.payload as AuroraPayload).forecastKind == AuroraForecastKind.NOWCAST }
        assertNotNull(nowcast, "a NOWCAST occurrence must be produced in this same refresh")
        assertEquals(now, nowcast.fetchedAt)
        assertTrue(result.newState.containsKey(AuroraSource.STATE_KEY_GRID), "the grid must be persisted for the visibility model to read later")
    }

    @Test
    fun ovationActivityFarFromEverySavedLocationProducesNoNowcastOccurrence() = runTest {
        // High probability exists, but nowhere near `home` (800km prefilter, §7.3.3).
        val ovation = ovationFixture(listOf(Triple(180, -60, 90)))
        val source = AuroraSource(mockClient(mutableListOf(), forecast = kpForecastFixture(kp = 7.0), ovation = ovation))

        val result = source.refresh(refreshRequest(locations = listOf(home), minKp = 5.0))

        assertTrue(result.occurrences.none { (it.payload as AuroraPayload).forecastKind == AuroraForecastKind.NOWCAST })
    }

    @Test
    fun threeDaySlotsBelowTheThresholdProduceNothing() = runTest {
        val forecast = """
            [["time_tag","kp","observed","noaa_scale"],
             ["${(now + 3.hours).toSwpcTimeString()}","3.00","predicted","G0"],
             ["${(now + 6.hours).toSwpcTimeString()}","6.00","predicted","G2"]]
        """.trimIndent()
        val source = AuroraSource(mockClient(mutableListOf(), forecast = forecast, ovation = null))

        val result = source.refresh(refreshRequest(locations = listOf(home), minKp = 5.0))

        val threeDay = result.occurrences.filter { (it.payload as AuroraPayload).forecastKind == AuroraForecastKind.THREE_DAY }
        assertEquals(1, threeDay.size, "only the 6.0 Kp slot clears the 5.0 threshold")
        assertEquals(6.0, (threeDay[0].payload as AuroraPayload).kpForecast)
    }

    @Test
    fun anOvationFailureDuringActiveTierDegradesGracefullyKeepingThreeDayOccurrences() = runTest {
        val requestedUrls = mutableListOf<String>()
        val engine = MockEngine { request ->
            requestedUrls += request.url.toString()
            when {
                request.url.toString().contains("planetary-k-index-forecast") ->
                    respond(kpForecastFixture(kp = 7.0), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                request.url.toString().contains("ovation_aurora_latest") -> respondError(HttpStatusCode.ServiceUnavailable)
                else -> error("unexpected URL: ${request.url}")
            }
        }
        val client = HttpClient(engine) {
            expectSuccess = true
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val source = AuroraSource(client)

        val result = source.refresh(refreshRequest(locations = listOf(home), minKp = 5.0))

        assertFalse(result.diagnostics.ok, "the OVATION failure must be surfaced (§19 R3)")
        assertTrue(result.occurrences.isNotEmpty(), "THREE_DAY occurrences from the (successful) forecast fetch must survive")
        assertTrue(result.occurrences.none { (it.payload as AuroraPayload).forecastKind == AuroraForecastKind.NOWCAST })
    }

    @Test
    fun aPersistedGridSurvivesTheGzipRoundTripThroughSourceStateRepo() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        SkywardDatabase.Schema.create(driver)
        val repo = SourceStateRepo(SkywardDatabase(driver))
        assertTrue(AuroraSource.loadOvationGrid(repo) == null, "nothing persisted yet")

        val ovation = ovationFixture(listOf(Triple(10, 60, 80)))
        val source = AuroraSource(mockClient(mutableListOf(), forecast = kpForecastFixture(kp = 7.0), ovation = ovation))
        val result = source.refresh(refreshRequest(locations = listOf(home), minKp = 5.0))
        for ((key, value) in result.newState) repo.upsert(AuroraSource.SOURCE_ID, key, value, now)

        val loaded = AuroraSource.loadOvationGrid(repo)
        assertNotNull(loaded)
        assertEquals(80, loaded.probabilityAt(GeoPoint(60.0, 10.0)).toInt())
    }

    private fun kpForecastFixture(kp: Double): String = """
        [["time_tag","kp","observed","noaa_scale"],
         ["${(now + 3.hours).toSwpcTimeString()}","$kp","predicted","G1"]]
    """.trimIndent()

    private fun ovationFixture(cells: List<Triple<Int, Int, Int>>): String {
        val coords = cells.joinToString(",") { (lon, lat, prob) -> "[$lon,$lat,$prob]" }
        return """{"Observation Time":"${now.toSwpcTimeString()}","Forecast Time":"${(now + 5.minutes).toSwpcTimeString()}","coordinates":[$coords]}"""
    }

    private fun mockClient(requestedUrls: MutableList<String>, forecast: String, ovation: String?): HttpClient {
        val engine = MockEngine { request ->
            requestedUrls += request.url.toString()
            val url = request.url.toString()
            when {
                url.contains("planetary-k-index-forecast") -> respond(forecast, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                url.contains("ovation_aurora_latest") && ovation != null -> respond(ovation, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                else -> error("unexpected/unhandled URL in this test: $url")
            }
        }
        return HttpClient(engine) {
            expectSuccess = true
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
    }

    private fun refreshRequest(locations: List<SavedLocation>, minKp: Double?) = RefreshRequest(
        now = now,
        horizon = TimeWindow(now, now + 365.days),
        locations = locations,
        state = emptyMap(),
        settings = SourceSettings(),
        derivedThresholds = DerivedThresholds(minKpOfInterest = minKp, maxCometMag = null, maxTravelKm = null),
    )

    private fun Instant.toSwpcTimeString(): String {
        val dt = toLocalDateTime(TimeZone.UTC)
        @Suppress("DEPRECATION")
        return "${dt.year}-${dt.monthNumber.pad2()}-${dt.dayOfMonth.pad2()} ${dt.hour.pad2()}:${dt.minute.pad2()}:${dt.second.pad2()}"
    }

    private fun Int.pad2(): String = toString().padStart(2, '0')
}
