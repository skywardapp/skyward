package dev.fritze.skyward.core.sources

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

/**
 * The JPL SBDB parser's edge cases: the Julian-date conversion, rows missing
 * the magnitude parameters, a malformed element, and a missing `fields`
 * header. Every input here is *synthetic*, written to provoke one specific
 * skip.
 *
 * §17.3's golden test over a real captured response of the query
 * `CometSource.discoveryUrl()` issues is `desktopTest`'s
 * `JplSbdbFixtureTest`, reading the file `tools/fixtures/fetch-jpl-sbdb.sh`
 * writes. See `docs/adr/0009-fixture-files-and-jvm-only-golden-tests.md`.
 */
class JplSbdbParsingTest {

    @Test
    fun julianDateConvertsUsingTheStatedEpoch() {
        // §7.4.1: "JD 2440587.5 = Unix epoch".
        assertEquals(Instant.fromEpochSeconds(0), julianDateToInstant(2440587.5))
        assertEquals(Instant.fromEpochSeconds(86400), julianDateToInstant(2440588.5))
    }

    @Test
    fun parsesRowsAndSkipsThoseMissingM1OrK1() {
        val candidates = parseJplSbdbQuery(SBDB_SAMPLE)

        assertEquals(2, candidates.size, "the M1-missing row (12P) must be skipped")
        val atlas = candidates.first { it.designation == "C/2025 K1" }
        assertEquals("(ATLAS)", atlas.name)
        assertEquals(8.5, atlas.magParams.m1)
        assertEquals(4.0, atlas.magParams.k1)
        assertEquals(0.999, atlas.elements.eccentricity)
        assertEquals(0.5, atlas.elements.perihelionDistanceAu)
        assertEquals(45.0, atlas.elements.inclinationDeg)
        assertEquals(120.0, atlas.elements.ascendingNodeDeg)
        assertEquals(80.0, atlas.elements.argPerihelionDeg)
    }

    @Test
    fun nameFieldIsWrappedInParensUnlessAlreadyPresent() {
        val candidates = parseJplSbdbQuery(SBDB_SAMPLE)
        val unnamed = candidates.first { it.designation == "C/2030 Z9" }
        assertNull(unnamed.name)
    }

    @Test
    fun malformedRowsAreSkippedNotFatal() {
        val withGarbage = """
            {"signature":{"version":"1.0"},"count":2,
             "fields":["pdes","name","epoch","e","q","i","om","w","tp","M1","K1"],
             "data":[
               ["C/2026 A1","(Test)","2461000.5","not-a-number","0.5","45.0","120.0","80.0","2461050.123","8.5","4.0"],
               ["C/2026 B2","(Good)","2461000.5","0.9","0.5","45.0","120.0","80.0","2461050.123","8.5","4.0"]
             ]}
        """.trimIndent()
        val candidates = parseJplSbdbQuery(withGarbage)
        assertEquals(1, candidates.size)
        assertEquals("C/2026 B2", candidates[0].designation)
    }

    @Test
    fun missingFieldsHeaderProducesNoCandidates() {
        assertEquals(0, parseJplSbdbQuery("""{"fields":["pdes"],"data":[["C/2026 A1"]]}""").size)
    }

    private companion object {
        val SBDB_SAMPLE = """
            {"signature":{"source":"NASA/JPL Small-Body Database (SBDB) API","version":"1.0"},
             "count":3,
             "fields":["full_name","pdes","name","epoch","e","q","i","om","w","tp","M1","K1","M2","K2"],
             "data":[
               [" C/2025 K1 (ATLAS)","C/2025 K1","ATLAS","2461000.5","0.999","0.5","45.0","120.0","80.0","2461050.123","8.5","4.0",null,null],
               [" 12P/Pons-Brooks","12P","Pons-Brooks","2460800.5","0.955","0.78","74.2","255.8","199.0","2460930.5",null,"12.0",null,null],
               [" C/2030 Z9","C/2030 Z9",null,"2461500.5","0.99","1.2","30.0","10.0","50.0","2461600.0","9.0","5.0",null,null]
             ]}
        """.trimIndent()
    }
}
