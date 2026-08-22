package dev.fritze.skyward.core.planner

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.fritze.skyward.core.model.GeoPoint
import dev.fritze.skyward.core.model.Occurrence
import dev.fritze.skyward.core.model.Phenomenon
import dev.fritze.skyward.core.model.PlannedNotification
import dev.fritze.skyward.core.model.SavedLocation
import dev.fritze.skyward.core.model.TimeWindow
import dev.fritze.skyward.core.persistence.SkywardDatabase
import dev.fritze.skyward.core.persistence.SourceStateRepo
import dev.fritze.skyward.core.rules.Rule
import dev.fritze.skyward.core.rules.defaultRules
import dev.fritze.skyward.core.sources.AuroraSource
import dev.fritze.skyward.core.sources.CometSource
import dev.fritze.skyward.core.sources.ConjunctionSource
import dev.fritze.skyward.core.sources.EclipseSource
import dev.fritze.skyward.core.sources.EonetSource
import dev.fritze.skyward.core.sources.EventSource
import dev.fritze.skyward.core.sources.MeteorShowerSource
import dev.fritze.skyward.core.sources.MoonEventSource
import dev.fritze.skyward.core.sources.RefreshRequest
import dev.fritze.skyward.core.sources.SourceSettings
import dev.fritze.skyward.core.sources.deriveThresholds
import dev.fritze.skyward.core.visibility.AuroraVisibilityModel
import dev.fritze.skyward.core.visibility.CometVisibilityModel
import dev.fritze.skyward.core.visibility.ConjunctionVisibilityModel
import dev.fritze.skyward.core.visibility.LunarEclipseVisibilityModel
import dev.fritze.skyward.core.visibility.MeteorShowerVisibilityModel
import dev.fritze.skyward.core.visibility.MoonEventVisibilityModel
import dev.fritze.skyward.core.visibility.SolarEclipseVisibilityModel
import dev.fritze.skyward.core.visibility.TerrestrialVisibilityModel
import dev.fritze.skyward.core.visibility.VisibilityContext
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
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
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * §17.6, extending [DeterminismGuardTest]: that guard runs only the four
 * COMPUTED sources, so natural-key/dedup drift in the POLLED pipelines
 * (Aurora/Comet/EONET, driven by HTTP responses rather than pure
 * computation) would slip through untested (issue #76). This is a JVM-only
 * `desktopTest` sibling rather than an addition to `DeterminismGuardTest`
 * itself, per ADR 0010: it needs `ktor-client-mock`, which `commonTest`
 * deliberately doesn't depend on (§15.3).
 *
 * Two runs, each from fresh source/HTTP-client instances against the same
 * fixed fixtures, must produce byte-identical occurrences and planned
 * notifications -- both in the exact order produced (pinning ordering
 * itself) and sort-normalised by natural key (content identity independent
 * of order). Cross-JVM-launch nondeterminism (e.g. a bug that only shows up
 * when hash-iteration order differs between launches) is out of scope for
 * an in-process guard like this one -- noted, not claimed to be covered,
 * per the issue's own "also... untested" aside.
 */
class DeterminismGuardPolledSourcesTest {

    private val visibilityModels = mapOf(
        Phenomenon.SOLAR_ECLIPSE to SolarEclipseVisibilityModel(),
        Phenomenon.LUNAR_ECLIPSE to LunarEclipseVisibilityModel(),
        Phenomenon.AURORA to AuroraVisibilityModel(),
        Phenomenon.METEOR_SHOWER to MeteorShowerVisibilityModel(),
        Phenomenon.COMET to CometVisibilityModel(),
        Phenomenon.MOON_EVENT to MoonEventVisibilityModel(),
        Phenomenon.CONJUNCTION to ConjunctionVisibilityModel(),
        Phenomenon.TERRESTRIAL to TerrestrialVisibilityModel(),
    )

    private data class PipelineRun(val occurrences: List<Occurrence>, val notifications: List<PlannedNotification>)

    private suspend fun runFullPipeline(now: Instant, horizonEnd: Instant, location: SavedLocation, rules: List<Rule>): PipelineRun {
        val req = RefreshRequest(
            now = now,
            horizon = TimeWindow(now, horizonEnd),
            locations = listOf(location),
            state = emptyMap(),
            settings = SourceSettings(),
            derivedThresholds = deriveThresholds(rules.filter { it.enabled }),
        )

        val computedSources: List<EventSource> = listOf(EclipseSource(), MeteorShowerSource(), MoonEventSource(), ConjunctionSource())
        val computedOccurrences = computedSources.flatMap { it.refresh(req).occurrences }

        val pollHttpClient = mockPolledSourcesHttpClient(now, location)
        val (auroraResult, cometResult, eonetResult) = try {
            Triple(
                AuroraSource(pollHttpClient).refresh(req),
                CometSource(pollHttpClient).refresh(req),
                EonetSource(pollHttpClient).refresh(req),
            )
        } finally {
            pollHttpClient.close()
        }

        // Mirrors how ReplanCoordinator actually wires the NOWCAST grid in
        // production (AuroraSource.loadOvationGrid, fed from persisted
        // source_state) rather than evaluating aurora visibility against no
        // grid at all -- the NOWCAST path is exactly the time-varying-id
        // case this guard needs to exercise (AuroraSource.kt's own comment
        // on `buildNowcastOccurrence`).
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val ovationGrid = try {
            SkywardDatabase.Schema.create(driver)
            val stateRepo = SourceStateRepo(SkywardDatabase(driver))
            for ((key, value) in auroraResult.newState) stateRepo.upsert(AuroraSource.SOURCE_ID, key, value, now)
            AuroraSource.loadOvationGrid(stateRepo)
        } finally {
            driver.close()
        }

        val occurrences = computedOccurrences + auroraResult.occurrences + cometResult.occurrences + eonetResult.occurrences
        val ctx = VisibilityContext(now = now, ovationGrid = ovationGrid)
        val matches = Planner.computeMatches(occurrences, listOf(location), rules, visibilityModels, ctx)
        val notifications = Planner.desiredNotifications(matches, now, TimeZone.UTC)
        return PipelineRun(occurrences, notifications)
    }

    @Test
    fun theFullPipelineIncludingPolledSourcesIsByteIdenticalAcrossTwoRuns() = runTest(timeout = 120.seconds) {
        // Noon UTC. `default:aurora-now` ships with no quiet hours (§9.6,
        // issue #57), so this is not load-bearing for the "au:now:" assertion
        // below the way it once was -- kept as a plain, unremarkable instant
        // rather than picked for any significance to this test.
        val now = Instant.parse("2026-01-01T12:00:00Z")
        val horizonEnd = Instant.parse("2028-01-01T12:00:00Z")
        val location = SavedLocation(
            id = "home", name = "Home", point = GeoPoint(48.1351, 11.5820),
            isPrimary = true, createdAt = now, modifiedAt = now,
        )
        // The default terrestrial rule ships disabled (§9.6) -- flip it on
        // here so EONET's occurrences flow all the way through to a planned
        // notification too, not just into the unmatched occurrence list.
        val rules = defaultRules(now).map { if (Phenomenon.TERRESTRIAL in it.phenomena) it.copy(enabled = true) else it }

        val firstRun = runFullPipeline(now, horizonEnd, location, rules)
        val secondRun = runFullPipeline(now, horizonEnd, location, rules)

        assertTrue(firstRun.occurrences.isNotEmpty(), "expected at least one occurrence to make this a meaningful check")
        assertTrue(firstRun.notifications.isNotEmpty(), "expected at least one planned notification to make this a meaningful check")

        // The three polled sources central to issue #76 must have actually
        // produced occurrences here, not merely been invoked.
        assertTrue(firstRun.occurrences.any { it.sourceId == AuroraSource.SOURCE_ID }, "AuroraSource must contribute occurrences")
        assertTrue(firstRun.occurrences.any { it.sourceId == "jpl" }, "CometSource must contribute occurrences")
        assertTrue(firstRun.occurrences.any { it.sourceId == "eonet" }, "EonetSource must contribute occurrences")
        // The NOWCAST path specifically: a time-varying id by design
        // (AuroraSource.kt), exactly the natural-key drift this guard exists
        // to catch, plus the EONET rule now enabled above.
        assertTrue(firstRun.notifications.any { it.occurrenceId.startsWith("au:now:") }, "the aurora NOWCAST path must reach a planned notification")
        assertTrue(firstRun.notifications.any { it.occurrenceId.startsWith("eo:") }, "the EONET path must reach a planned notification")

        assertEquals(firstRun.occurrences, secondRun.occurrences, "the occurrence pipeline (incl. polled sources) must be deterministic run-to-run")
        assertEquals(firstRun.notifications, secondRun.notifications, "the full pipeline must be deterministic run-to-run")

        // Byte-identical ids specifically, since that's the natural-key/dedup
        // property this guard exists to protect (§17.6's own framing).
        assertEquals(firstRun.occurrences.map { it.id }, secondRun.occurrences.map { it.id })
        assertEquals(firstRun.notifications.map { it.id }, secondRun.notifications.map { it.id })
        assertEquals(firstRun.notifications.map { it.fireAt }, secondRun.notifications.map { it.fireAt })

        // Sort-normalised on top of the exact-order checks above: this one
        // is about content identity independent of iteration order, so an
        // order-only regression (e.g. hash-based collection ordering
        // leaking into output order) can't hide behind a coincidentally
        // matching sort key the way it could if this were the only check.
        assertEquals(
            firstRun.occurrences.sortedBy { it.id },
            secondRun.occurrences.sortedBy { it.id },
            "occurrence content must match even ignoring order",
        )
        assertEquals(
            firstRun.notifications.sortedBy { it.id },
            secondRun.notifications.sortedBy { it.id },
            "notification content must match even ignoring order",
        )
    }

    /**
     * One mock engine serving all four POLLED-source URLs used by this
     * test's sources.
     */
    private fun mockPolledSourcesHttpClient(now: Instant, location: SavedLocation): HttpClient {
        val engine = MockEngine { request ->
            val url = request.url.toString()
            when {
                url.contains("planetary-k-index-forecast") -> respond(kpForecastFixture(now), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                url.contains("ovation_aurora_latest") -> respond(ovationFixture(now, location), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                url.contains("sbdb_query.api") -> respond(cometFixture(now), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                url.contains("eonet.gsfc.nasa.gov") -> respond(eonetFixture(now, location), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                else -> error("unexpected/unhandled URL in this test: $url")
            }
        }
        return HttpClient(engine) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
    }

    /**
     * A single slot at Kp 7 -- above every enabled rule's `KpAtLeast`
     * threshold, so both THREE_DAY ingest and the OVATION active tier
     * trigger.
     */
    private fun kpForecastFixture(now: Instant): String = """
        [["time_tag","kp","observed","noaa_scale"],
         ["${(now + 3.hours).toSwpcTimeString()}","7.00","predicted","G3"]]
    """.trimIndent()

    /**
     * One high-probability cell close enough to bilinearly interpolate
     * above both the 10% ingest prefilter and the 25% MARGINAL nowcast
     * quality gate at [location].
     */
    private fun ovationFixture(now: Instant, location: SavedLocation): String {
        val lon = location.point.lonDeg.toInt() + 1
        val lat = location.point.latDeg.toInt()
        return """{"Observation Time":"${now.toSwpcTimeString()}","Forecast Time":"${(now + 5.minutes).toSwpcTimeString()}","coordinates":[[$lon,$lat,80]]}"""
    }

    /**
     * A bright comet fixture (same shape as CometSourceTest's) with
     * perihelion inside the test's 2-year horizon, guaranteed to clear the
     * fixed 6.0 ingest floor.
     */
    private fun cometFixture(now: Instant): String {
        val tp = now + 45.days
        fun instantToJulianDate(instant: Instant): Double = instant.epochSeconds / 86400.0 + 2440587.5
        return """
            {"signature":{"version":"1.0"},"count":1,
             "fields":["full_name","pdes","name","epoch","e","q","i","om","w","tp","M1","K1","M2","K2"],
             "data":[[" C/2026 D1","C/2026 D1","Test",${instantToJulianDate(now)},0.99,0.4,20.0,50.0,80.0,${instantToJulianDate(tp)},4.0,10.0,null,null]]}
        """.trimIndent()
    }

    /**
     * An open volcano event within `default:volcano-within-reach`'s 300km
     * reach of [location].
     */
    private fun eonetFixture(now: Instant, location: SavedLocation): String {
        val eventLat = location.point.latDeg + 0.5
        val eventLon = location.point.lonDeg + 0.5
        return """
            {"events":[
              {"id":"EONET_9001","title":"Test volcano","link":"https://eonet.gsfc.nasa.gov/api/v3/events/EONET_9001",
               "categories":[{"id":"volcanoes","title":"Volcanoes"}],
               "geometry":[{"date":"${(now - 1.days).toSwpcTimeString()}","type":"Point","coordinates":[$eventLon,$eventLat]}]}
            ]}
        """.trimIndent()
    }

    private fun Instant.toSwpcTimeString(): String {
        val dt = toLocalDateTime(TimeZone.UTC)
        @Suppress("DEPRECATION")
        return "${dt.year}-${dt.monthNumber.pad2()}-${dt.dayOfMonth.pad2()} ${dt.hour.pad2()}:${dt.minute.pad2()}:${dt.second.pad2()}"
    }

    private fun Int.pad2(): String = toString().padStart(2, '0')
}
