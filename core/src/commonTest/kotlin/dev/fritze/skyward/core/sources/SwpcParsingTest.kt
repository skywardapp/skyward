package dev.fritze.skyward.core.sources

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The SWPC parsers' edge cases: column reordering, malformed rows, both row
 * encodings, and the exact OVATION byte layout. Every input here is
 * *synthetic* -- written to provoke a specific failure, which a real capture
 * cannot be relied on to contain.
 *
 * §17.3's golden tests over real captured responses (including a full-size
 * OVATION grid) are `desktopTest`'s `SwpcFixtureTest`, reading the files
 * `tools/fixtures/fetch-swpc.sh` writes. See
 * `docs/adr/0010-fixture-files-and-jvm-only-golden-tests.md` for the split.
 */
class SwpcParsingTest {

    @Test
    fun parsesTheProductsHeaderRowsShapeWithStringValues() {
        val slots = parseSwpcKpForecast(KP_FORECAST_SAMPLE)
        assertEquals(4, slots.size)
        assertEquals(Instant.parse("2026-08-12T12:00:00Z"), slots[0].time)
        assertEquals(5.33, slots[0].kp)
        assertEquals("predicted", slots[0].state)
        assertEquals(7.67, slots[3].kp)
    }

    @Test
    fun columnOrderIsResolvedByHeaderNotPosition() {
        // Same data, "kp" and "observed" columns swapped relative to the sample above.
        val reordered = """
            [["time_tag","observed","kp","noaa_scale"],
             ["2026-08-12 12:00:00","predicted","5.33","G1"]]
        """.trimIndent()
        val slots = parseSwpcKpForecast(reordered)
        assertEquals(1, slots.size)
        assertEquals(5.33, slots[0].kp)
        assertEquals("predicted", slots[0].state)
    }

    @Test
    fun malformedRowsAreSkippedNotFatal() {
        val withGarbage = """
            [["time_tag","kp","observed","noaa_scale"],
             ["2026-08-12 12:00:00","5.33","predicted","G1"],
             ["not-a-time","5.33","predicted","G1"],
             ["2026-08-12 15:00:00","not-a-number","predicted","G1"],
             ["2026-08-12 18:00:00"],
             ["2026-08-12 21:00:00","6.00","predicted","G2"]]
        """.trimIndent()
        val slots = parseSwpcKpForecast(withGarbage)
        assertEquals(2, slots.size, "only the two well-formed rows should survive")
        assertEquals(5.33, slots[0].kp)
        assertEquals(6.00, slots[1].kp)
    }

    @Test
    fun theObjectRowShapeParsesToTheSameSlots() {
        // SWPC also serves this file as plain objects with a numeric `kp`
        // (the shape the checked-in capture has). Same slots, no header row.
        val objects = """
            [{"time_tag":"2026-08-12T12:00:00","kp":5.33,"observed":"predicted","noaa_scale":"G1"},
             {"time_tag":"2026-08-12T15:00:00","kp":6.00,"observed":"observed","noaa_scale":null}]
        """.trimIndent()
        val slots = parseSwpcKpForecast(objects)
        assertEquals(2, slots.size)
        assertEquals(Instant.parse("2026-08-12T12:00:00Z"), slots[0].time)
        assertEquals(5.33, slots[0].kp)
        assertEquals("predicted", slots[0].state)
        assertEquals("observed", slots[1].state)
    }

    @Test
    fun anObjectRowWithNoStateReportsNoStateRatherThanTheStringNull() {
        val objects = """[{"time_tag":"2026-08-12T12:00:00","kp":5.33,"observed":null}]"""
        assertEquals(null, parseSwpcKpForecast(objects).single().state)
        assertEquals(null, parseSwpcKpForecast("""[{"time_tag":"2026-08-12T12:00:00","kp":5.33}]""").single().state)
    }

    @Test
    fun malformedObjectRowsAreSkippedNotFatal() {
        val objects = """
            [{"time_tag":"2026-08-12T12:00:00","kp":5.33},
             {"time_tag":"not-a-time","kp":5.33},
             {"time_tag":"2026-08-12T15:00:00"},
             {"time_tag":"2026-08-12T18:00:00","kp":"NaN"},
             {"time_tag":"2026-08-12T21:00:00","kp":6.00}]
        """.trimIndent()
        val slots = parseSwpcKpForecast(objects)
        assertEquals(2, slots.size, "only the two well-formed rows should survive")
        assertEquals(6.00, slots[1].kp)
    }

    @Test
    fun emptyOrHeaderOnlyInputProducesNoSlots() {
        assertEquals(0, parseSwpcKpForecast("[]").size)
        assertEquals(0, parseSwpcKpForecast("""[["time_tag","kp","observed","noaa_scale"]]""").size)
    }

    @Test
    fun parsesTheOvationJsonObjectShapeIntoTheExpectedByteLayout() {
        val parsed = parseOvationGridJson(OVATION_SAMPLE)
        assertEquals(Instant.parse("2026-08-12T21:50:00Z"), parsed.observationTime)
        assertEquals(Instant.parse("2026-08-12T21:55:00Z"), parsed.forecastTime)
        assertEquals(360 * 181, parsed.probBytes.size)
        assertEquals(6, parsed.cellsParsed)

        // (lon=0, lat=-90) -> index (0*181)+(−90+90) = 0
        assertEquals(12, parsed.probBytes[0].toInt())
        // (lon=180, lat=0) -> index (180*181)+(0+90) = 32670
        assertEquals(75, parsed.probBytes[(180 * 181) + 90].toInt())
        // (lon=359, lat=90) -> index (359*181)+(90+90) = 65159 (last cell, wrap boundary)
        assertEquals(90, parsed.probBytes[(359 * 181) + 180].toInt())
    }

    @Test
    fun truncatedOvationResponseDegradesGracefullyInsteadOfCrashing() {
        val truncated = """{"Observation Time":"2026-08-12 21:50:00","Forecast Time":"2026-08-12 21:55:00","coordinates":[[10,20,5</chunk-cut-here"""
        // A genuinely truncated JSON payload throws (structurally invalid) -- the
        // *source*, not this parser, is responsible for turning that into a
        // diagnostic (§19 R3); this parser's own "graceful" contract is about
        // well-formed-JSON-but-incomplete/garbage *cell* data, covered below.
        assertTrue(runCatching { parseOvationGridJson(truncated) }.isFailure)

        val partialCells = """
            {"Observation Time":"2026-08-12 21:50:00","Forecast Time":"2026-08-12 21:55:00",
             "coordinates":[[10,20,5],[999,20,5],["oops",20,5],[10,20]]}
        """.trimIndent()
        val parsed = parseOvationGridJson(partialCells)
        assertEquals(360 * 181, parsed.probBytes.size, "buffer is always the full fixed size regardless of how many cells parsed")
        // [10,20,5] and [999,20,5] (longitude wraps rather than being rejected)
        // both parse; ["oops",20,5] (non-numeric) and [10,20] (too short) don't.
        assertEquals(2, parsed.cellsParsed)
        assertEquals(5, parsed.probBytes[(10 * 181) + (20 + 90)].toInt())
    }

    @Test
    fun parsesBothSwpcTimestampShapes() {
        assertEquals(Instant.parse("2026-08-12T12:00:00Z"), parseUtcNoZoneInstant("2026-08-12 12:00:00"))
        assertEquals(Instant.parse("2026-08-12T12:00:00Z"), parseUtcNoZoneInstant("2026-08-12T12:00:00Z"))
    }

    private companion object {
        val KP_FORECAST_SAMPLE = """
            [["time_tag","kp","observed","noaa_scale"],
             ["2026-08-12 12:00:00","5.33","predicted","G1"],
             ["2026-08-12 15:00:00","6.00","predicted","G2"],
             ["2026-08-12 18:00:00","6.67","predicted","G3"],
             ["2026-08-12 21:00:00","7.67","predicted","G4"]]
        """.trimIndent()

        // Six coordinate triples: both longitude ends (wrap boundary), both
        // latitude poles, and two interior points -- the exact
        // `(lon*181)+(lat+90)` layout, addressable by hand. The full 65160-cell
        // grid is covered by the captured fixture in SwpcFixtureTest.
        val OVATION_SAMPLE = """
            {"Observation Time":"2026-08-12 21:50:00","Forecast Time":"2026-08-12 21:55:00",
             "coordinates":[
               [0,-90,12],
               [0,90,34],
               [180,0,75],
               [359,90,90],
               [45,10,3],
               [270,-45,60]
             ]}
        """.trimIndent()
    }
}
