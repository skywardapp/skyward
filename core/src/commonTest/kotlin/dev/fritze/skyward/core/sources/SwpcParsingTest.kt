package dev.fritze.skyward.core.sources

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * §17.3: golden-sample parser tests. Fixture JSON is embedded as checked-in
 * string constants (below) rather than loaded from `commonTest/resources/`
 * -- these parsers are pure functions of a `String`, so a resource-loading
 * `expect/actual` (§16's `ShowersResource` pattern) would add real KMP
 * plumbing (and an AGP androidMain-resource-duplication trap, per that
 * file's own comment) for no benefit over embedding here.
 */
class SwpcParsingTest {

    @Test
    fun parsesTheProductsHeaderRowsShapeWithStringValues() {
        val slots = parseSwpcKpForecast(KP_FORECAST_FIXTURE)
        assertEquals(4, slots.size)
        assertEquals(Instant.parse("2026-08-12T12:00:00Z"), slots[0].time)
        assertEquals(5.33, slots[0].kp)
        assertEquals("predicted", slots[0].state)
        assertEquals(7.67, slots[3].kp)
    }

    @Test
    fun columnOrderIsResolvedByHeaderNotPosition() {
        // Same data, "kp" and "observed" columns swapped relative to the main fixture.
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
    fun emptyOrHeaderOnlyInputProducesNoSlots() {
        assertEquals(0, parseSwpcKpForecast("[]").size)
        assertEquals(0, parseSwpcKpForecast("""[["time_tag","kp","observed","noaa_scale"]]""").size)
    }

    @Test
    fun parsesTheOvationJsonObjectShapeIntoTheExpectedByteLayout() {
        val parsed = parseOvationGridJson(OVATION_FIXTURE)
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
        val KP_FORECAST_FIXTURE = """
            [["time_tag","kp","observed","noaa_scale"],
             ["2026-08-12 12:00:00","5.33","predicted","G1"],
             ["2026-08-12 15:00:00","6.00","predicted","G2"],
             ["2026-08-12 18:00:00","6.67","predicted","G3"],
             ["2026-08-12 21:00:00","7.67","predicted","G4"]]
        """.trimIndent()

        // Six coordinate triples: both longitude ends (wrap boundary), both
        // latitude poles, and two interior points -- enough to exercise the
        // exact `(lon*181)+(lat+90)` layout without shipping a ~65k-entry fixture.
        val OVATION_FIXTURE = """
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
