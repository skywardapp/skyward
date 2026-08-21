package dev.fritze.skyward.core.sources

import dev.fritze.skyward.core.testing.Fixtures
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * §17.3: the SWPC parsers against real captured responses rather than
 * hand-written approximations of them. Regenerate with
 * `tools/fixtures/fetch-swpc.sh`.
 *
 * These assert *shape* invariants -- row counts, grid extent, value ranges,
 * ordering -- not the particular numbers in the current capture, which change
 * on every refresh. A refresh that breaks one of them is a format drift
 * (§19 R3), which is the whole reason to keep a captured response around.
 */
class SwpcFixtureTest {

    @Test
    fun everySlotOfTheCapturedKpForecastParses() {
        val raw = Fixtures.text("swpc_planetary_k_index_forecast.json")
        val slots = parseSwpcKpForecast(raw)

        assertTrue(slots.size >= 20, "expected a multi-day 3-hourly forecast, parsed ${slots.size} slots")
        // Rows the parser can't read are skipped rather than fatal, so a count
        // short of what the payload holds is precisely the failure a golden
        // test has to catch -- it is invisible at runtime.
        assertEquals(rowsInCapture(raw), slots.size, "some rows in the capture were skipped")

        for (slot in slots) {
            assertTrue(slot.kp in 0.0..9.0, "Kp out of range at ${slot.time}: ${slot.kp}")
        }
        assertEquals(slots.sortedBy { it.time }, slots, "slots should come back in the order SWPC sent them")
        // §7.3.3 reads this field to tell a still-forecast slot from an observed one.
        assertTrue(slots.any { it.state != null }, "expected the observed/estimated/predicted state to survive parsing")
        assertTrue(slots.none { it.state == "null" }, "a JSON null state must not arrive as the string \"null\"")
    }

    @Test
    fun theCapturedNowcastParsesEveryMinuteItContains() {
        val raw = Fixtures.text("swpc_planetary_k_index_1m.json")
        val estimates = KpNowcast.parseEstimatedKp1m(raw)

        assertEquals(rowsInCapture(raw), estimates.size, "some minutes in the capture were skipped")
        for (estimate in estimates) {
            assertTrue(estimate.estimatedKp in 0.0..9.0, "Kp out of range at ${estimate.time}: ${estimate.estimatedKp}")
        }
        assertEquals(estimates.sortedBy { it.time }, estimates, "minutes should come back in the order SWPC sent them")
    }

    /**
     * §17.3 asks for a full-size OVATION grid specifically. A trimmed one can
     * be indexed correctly while the real 360x181 payload isn't: the
     * `(lon*181)+(lat+90)` layout only goes wrong at the extremes, and only a
     * complete grid has them.
     */
    @Test
    fun theCapturedOvationGridFillsEveryCellOfTheExpectedLayout() {
        val parsed = parseOvationGridJson(Fixtures.text("swpc_ovation_aurora_latest.json"))

        assertEquals(GRID_CELLS, parsed.probBytes.size)
        assertEquals(GRID_CELLS, parsed.cellsParsed, "a full-size capture should leave no cell unparsed")
        assertTrue(parsed.forecastTime >= parsed.observationTime, "the forecast cannot precede its observation")

        for (byte in parsed.probBytes) {
            assertTrue(byte.toInt() in 0..100, "aurora probability out of range: $byte")
        }

        // Aurora is a high-latitude phenomenon. A grid that is all zeroes, or
        // one whose strongest cells sit near the equator, means the lon and
        // lat axes were transposed on the way in -- which no amount of
        // per-cell range checking would notice.
        val peakLatitude = (0..180).maxBy { latIndex ->
            (0 until 360).maxOf { lon -> parsed.probBytes[(lon * 181) + latIndex].toInt() }
        } - 90
        assertTrue(abs(peakLatitude) > 45, "expected the strongest cells at high latitude, found the peak at $peakLatitude deg")
    }

    /**
     * How many data rows the capture holds. Both shapes SWPC serves are
     * top-level arrays of rows; only the header-row shape spends its first
     * row on column names.
     */
    private fun rowsInCapture(raw: String): Int {
        val rows = Json.parseToJsonElement(raw).jsonArray.size
        return if (raw.trimStart().startsWith("[[")) rows - 1 else rows
    }

    private companion object {
        const val GRID_CELLS = 360 * 181
    }
}
