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
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
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
        assertEquals("cm:C2026T1", occ.id)
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
    fun peakMagDateIsStableAcrossRefreshesWhilePerihelionIsStillMoreThanNineMonthsOut() = runTest {
        // The regime §7.4.3's scan-range formula doesn't cover: with `now`
        // more than 9 months before perihelion, `min(now, tp-9mo)` *is*
        // `now`, so the range's start -- and with it the phase of a grid
        // stepped from that start -- moves with every refresh. The peak has
        // to stay put anyway: a comet bright enough to clear the ingest
        // floor a year out would otherwise re-plan (and re-fire its already
        // FIRED "7 days before peak" lead) at every monthly refresh.
        val tp = now + 400.days
        val json = sbdbFixture(tp = tp, epoch = now, e = 0.99, q = 0.4, i = 20.0, om = 50.0, w = 80.0, m1 = 4.0, k1 = 10.0, pdes = "C/2026 P1")

        val firstRun = CometSource(mockClient(json)).refresh(refreshRequest(now)).occurrences.single()
        // Not a whole number of days later: the 30-day schedule (§7.4) fires
        // whenever the app next runs, so consecutive refreshes land at
        // arbitrary times of day. A grid stepped from `now` is only stable
        // for the exact-multiple-of-a-day case that never actually happens.
        val monthLater = CometSource(mockClient(json))
            .refresh(refreshRequest(now + 30.days + 7.hours + 13.minutes))
            .occurrences.single()

        val first = firstRun.payload as CometPayload
        val second = monthLater.payload as CometPayload
        assertEquals(first.peakMagDate, second.peakMagDate, "§7.4.3: peakMagDate is stable across refreshes")
        assertEquals(first.peakMag, second.peakMag)
        // Identical peakTime is what keeps §6.3 from scoring the refresh
        // material (5-minute threshold) and re-planning; the window is
        // grid-derived too, so it must not drift either.
        assertEquals(firstRun.peakTime, monthLater.peakTime)
        assertEquals(firstRun.window, monthLater.window)
        assertTrue(isMaterialChange(firstRun, monthLater).not(), "a refresh that only advanced `now` must not count as a material change (§6.3)")
    }

    @Test
    fun cometsDimmerThanTheIngestFloorProduceNoOccurrence() = runTest {
        val tp = now + 45.days
        val json = sbdbFixture(tp = tp, epoch = now, e = 0.5, q = 3.0, i = 5.0, om = 10.0, w = 10.0, m1 = 20.0, k1 = 5.0, pdes = "C/2026 F1")

        val result = CometSource(mockClient(json)).refresh(refreshRequest(now))

        assertEquals(0, result.occurrences.size)
        assertTrue(result.diagnostics.ok)
        assertEquals(null, result.diagnostics.message, "filtered-by-ingest-floor is normal, silent filtering -- not a propagator-non-convergence diagnostic")
    }

    @Test
    fun peakMagDateStaysStableEvenWhenNowIsWellPastTpPlusNineMonths() = runTest {
        // §7.4.3's own scan-range formula is `min(now, tp-9mo)..max(now, tp+9mo)`
        // -- deliberately widening past the +/-9mo window when `now` sits outside
        // it, per the design doc, rather than a fixed tp+/-9mo window. This proves
        // that widening the scan (here, `now` is far past tp+9mo) still finds the
        // same physical minimum: post-perihelion brightness only fades (r and
        // delta both grow), so extending the scan can't introduce a brighter,
        // later minimum to displace the one near perihelion.
        val tp = now + 10.days
        val json = sbdbFixture(tp = tp, epoch = now, e = 0.99, q = 0.4, i = 20.0, om = 50.0, w = 80.0, m1 = 4.0, k1 = 10.0, pdes = "C/2026 W1")

        val firstRun = (CometSource(mockClient(json)).refresh(refreshRequest(now)).occurrences.single().payload as CometPayload)
        val farFutureNow = tp + 400.days // well past tp + NINE_MONTHS (274.days)
        val secondRun = (CometSource(mockClient(json)).refresh(refreshRequest(farFutureNow)).occurrences.single().payload as CometPayload)

        assertEquals(firstRun.peakMagDate, secondRun.peakMagDate)
        assertEquals(firstRun.peakMag, secondRun.peakMag)
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
