package dev.fritze.skyward.core.sources

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * §17.3 for the 1-minute estimated-Kp product that backs §14.4's gauge.
 * Fixture embedded as a string constant for the same reason
 * [SwpcParsingTest] does it: these parsers are pure functions of a `String`.
 */
class KpNowcastTest {

    @Test
    fun parsesTheJsonObjectArrayShape() {
        val samples = KpNowcast.parseEstimatedKp1m(FIXTURE)
        assertEquals(3, samples.size)
        assertEquals(Instant.parse("2026-08-14T19:57:00Z"), samples[0].time)
        assertEquals(4.33, samples[0].estimatedKp)
    }

    @Test
    fun latestIsSelectedByTimeNotByPosition() {
        // SWPC has been known to reorder rows; taking the last element on
        // faith would then show a stale reading as "now".
        val latest = KpNowcast.parseEstimatedKp1m(FIXTURE).maxByOrNull { it.time }
        assertEquals(Instant.parse("2026-08-14T19:59:00Z"), latest?.time)
        assertEquals(5.67, latest?.estimatedKp)
    }

    @Test
    fun fallsBackToKpIndexWhenTheEstimateIsAbsent() {
        val samples = KpNowcast.parseEstimatedKp1m(
            """[{"time_tag":"2026-08-14 20:00:00.000","kp_index":3.0,"kp":"3"}]""",
        )
        assertEquals(3.0, samples.single().estimatedKp)
    }

    @Test
    fun aBadMinuteIsSkippedRatherThanBlankingTheGauge() {
        val samples = KpNowcast.parseEstimatedKp1m(
            """
            [
              {"time_tag":"not a timestamp","estimated_kp":4.0},
              {"time_tag":"2026-08-14 20:01:00.000"},
              {"estimated_kp":9.0},
              {"kp_index":"not a number","time_tag":"2026-08-14 20:03:00.000"},
              {"time_tag":"2026-08-14 20:02:00.000","estimated_kp":2.5}
            ]
            """.trimIndent(),
        )
        // A row with no `time_tag` at all is the interesting one: decoding the
        // whole array as List<Row> would throw on it and lose the good rows too.
        assertEquals(1, samples.size)
        assertEquals(2.5, samples.single().estimatedKp)
    }

    @Test
    fun theEndpointIsTheOneTheDesignDocNames() {
        // §7.3.1's table; a silent URL drift is exactly the §19 R3 failure mode.
        assertTrue(KpNowcast.URL == "https://services.swpc.noaa.gov/json/planetary_k_index_1m.json", KpNowcast.URL)
    }

    private companion object {
        // Shape per §7.3.1: files under /json/ are conventional object arrays.
        val FIXTURE = """
            [
              {"time_tag":"2026-08-14 19:57:00.000","kp_index":4.0,"estimated_kp":4.33,"kp":"4"},
              {"time_tag":"2026-08-14 19:59:00.000","kp_index":6.0,"estimated_kp":5.67,"kp":"6-"},
              {"time_tag":"2026-08-14 19:58:00.000","kp_index":5.0,"estimated_kp":5.00,"kp":"5"}
            ]
        """.trimIndent()
    }
}
