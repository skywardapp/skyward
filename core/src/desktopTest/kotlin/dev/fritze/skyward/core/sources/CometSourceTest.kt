package dev.fritze.skyward.core.sources

import dev.fritze.skyward.core.model.Certainty
import dev.fritze.skyward.core.model.CometPayload
import dev.fritze.skyward.core.model.TimeWindow
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/** §17.3/§18: the "known bright-comet fixture produces an occurrence with a sane peak date" accept criterion, plus §7.4.3's orbit-anchored-scan stability guarantee. */
class CometSourceTest {

    private val now = Instant.parse("2026-01-01T00:00:00Z")

    @Test
    fun aKnownBrightCometFixtureProducesAnOccurrenceWithASanePeakDate() = runTest {
        val tp = now + 45.days
        val json = sbdbFixture(tp = tp, epoch = now, e = 0.99, q = 0.4, i = 20.0, om = 50.0, w = 80.0, m1 = 4.0, k1 = 10.0, pdes = "C/2026 T1")

        val result = CometSource(mockClient(json)).refresh(refreshRequest(now))

        assertEquals(1, result.occurrences.size)
        val occ = result.occurrences.single()
        val payload = occ.payload as CometPayload
        assertEquals("comet:C/2026 T1", occ.id)
        assertEquals(Certainty.FORECAST, occ.certainty)
        assertEquals(now + 45.days, payload.perihelionDate)
        assertTrue(payload.peakMag <= 6.0, "must have cleared the ingest floor to have been emitted at all: ${payload.peakMag}")
        // "Sane" per the §18 accept criterion: within the ~9-month scan
        // window around perihelion, not merely non-null/non-crashing.
        assertTrue((payload.peakMagDate - tp).absoluteValue < 280.days, "peak ${payload.peakMagDate} should be within the scan window around perihelion $tp")
    }

    @Test
    fun peakMagDateIsStableAcrossRefreshesEvenAsNowAdvancesPastPerihelion() = runTest {
        val tp = now + 10.days
        val json = sbdbFixture(tp = tp, epoch = now, e = 0.99, q = 0.4, i = 20.0, om = 50.0, w = 80.0, m1 = 4.0, k1 = 10.0, pdes = "C/2026 S1")

        val firstRun = (CometSource(mockClient(json)).refresh(refreshRequest(now)).occurrences.single().payload as CometPayload)
        val laterNow = now + 60.days // well past perihelion -- a now-anchored scan would have slid forward
        val secondRun = (CometSource(mockClient(json)).refresh(refreshRequest(laterNow)).occurrences.single().payload as CometPayload)

        assertEquals(firstRun.peakMagDate, secondRun.peakMagDate, "§7.4.3: orbit-anchored scan keeps peakMagDate stable across refreshes")
        assertEquals(firstRun.peakMag, secondRun.peakMag)
    }

    @Test
    fun cometsDimmerThanTheIngestFloorProduceNoOccurrence() = runTest {
        val tp = now + 45.days
        val json = sbdbFixture(tp = tp, epoch = now, e = 0.5, q = 3.0, i = 5.0, om = 10.0, w = 10.0, m1 = 20.0, k1 = 5.0, pdes = "C/2026 F1")

        val result = CometSource(mockClient(json)).refresh(refreshRequest(now))

        assertEquals(0, result.occurrences.size)
    }

    @Test
    fun aHigherEnabledRuleThresholdRaisesTheIngestFloorAboveSix() = runTest {
        val tp = now + 45.days
        // M1=15 keeps this comet dimmer than mag 6 under any plausible
        // delta/r geometry for q=2.0 (m = 15 + K1*log10(2.0) + 5*log10(delta),
        // i.e. never brighter than ~mag 11 even at an unrealistically close
        // delta) -- but a generously raised 30.0 floor admits it regardless
        // of exactly how bright it turns out to be, so this doesn't depend
        // on hand-predicting the propagator's exact output.
        val json = sbdbFixture(tp = tp, epoch = now, e = 0.5, q = 2.0, i = 5.0, om = 10.0, w = 10.0, m1 = 15.0, k1 = 5.0, pdes = "C/2026 M1")
        val source = CometSource(mockClient(json))

        val defaultFloorResult = source.refresh(refreshRequest(now, maxCometMag = null))
        val raisedFloorResult = source.refresh(refreshRequest(now, maxCometMag = 30.0))

        assertEquals(0, defaultFloorResult.occurrences.size)
        assertEquals(1, raisedFloorResult.occurrences.size)
    }

    @Test
    fun rowsMissingM1OrK1AreSkippedWithoutFailingTheRefresh() = runTest {
        val json = """
            {"fields":["pdes","name","epoch","e","q","i","om","w","tp","M1","K1"],
             "data":[["C/2026 X1","(X)","2461000.5","0.9","0.5","10","20","30","2461050.5",null,"4.0"]]}
        """.trimIndent()

        val result = CometSource(mockClient(json)).refresh(refreshRequest(now))

        assertEquals(0, result.occurrences.size)
        assertTrue(result.diagnostics.ok)
    }

    private fun instantToJulianDate(instant: Instant): Double = instant.epochSeconds / 86400.0 + 2440587.5

    private fun sbdbFixture(tp: Instant, epoch: Instant, e: Double, q: Double, i: Double, om: Double, w: Double, m1: Double, k1: Double, pdes: String): String = """
        {"signature":{"version":"1.0"},"count":1,
         "fields":["full_name","pdes","name","epoch","e","q","i","om","w","tp","M1","K1","M2","K2"],
         "data":[[" $pdes","$pdes","Test",${instantToJulianDate(epoch)},$e,$q,$i,$om,$w,${instantToJulianDate(tp)},$m1,$k1,null,null]]}
    """.trimIndent()

    private fun mockClient(json: String): HttpClient {
        val engine = MockEngine { respond(json, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) }
        return HttpClient(engine) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
    }

    private fun refreshRequest(now: Instant, maxCometMag: Double? = null) = RefreshRequest(
        now = now,
        horizon = TimeWindow(now, now + 365.days),
        locations = emptyList(),
        state = emptyMap(),
        settings = SourceSettings(),
        derivedThresholds = DerivedThresholds(minKpOfInterest = null, maxCometMag = maxCometMag, maxTravelKm = null),
    )
}
