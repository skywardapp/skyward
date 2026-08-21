package dev.fritze.skyward.core.sources

import dev.fritze.skyward.core.testing.Fixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * §17.3: the JPL SBDB parser against a real captured response of the exact
 * query `CometSource.discoveryUrl()` issues. Regenerate with
 * `tools/fixtures/fetch-jpl-sbdb.sh`.
 *
 * D12: these elements come from JPL rather than COBS because JPL's data is
 * public domain and COBS' licence forbids commercial use (§16).
 */
class JplSbdbFixtureTest {

    @Test
    fun theCapturedQueryParsesIntoCandidatesWithUsableElements() {
        val candidates = parseJplSbdbQuery(Fixtures.text("jpl_sbdb_comets.json"))

        assertTrue(candidates.size >= 100, "expected a large comet catalogue, parsed ${candidates.size}")
        assertEquals(candidates.map { it.designation }.distinct().size, candidates.size, "designations should be unique")

        for (candidate in candidates) {
            val elements = candidate.elements
            assertTrue(candidate.designation.isNotBlank(), "a candidate came back with no designation")
            assertTrue(elements.eccentricity >= 0.0, "${candidate.designation}: negative eccentricity")
            assertTrue(elements.perihelionDistanceAu > 0.0, "${candidate.designation}: non-positive perihelion distance")
            // The captured query constrains q < 4.5 au; anything past that
            // means the constraint isn't reaching the API.
            assertTrue(elements.perihelionDistanceAu < 4.5, "${candidate.designation}: q outside the queried range")
            assertTrue(elements.inclinationDeg in 0.0..180.0, "${candidate.designation}: inclination out of range")
            assertTrue(elements.ascendingNodeDeg in 0.0..360.0, "${candidate.designation}: node out of range")
            assertTrue(elements.argPerihelionDeg in 0.0..360.0, "${candidate.designation}: argument of perihelion out of range")
            assertTrue(candidate.magParams.m1 < 14.0, "${candidate.designation}: M1 outside the queried range")
        }
    }

    @Test
    fun aWellKnownCometComesBackWithTheElementsItShouldHave() {
        // 1P/Halley's elements are the most-published orbit there is, so they
        // are the one row in a live capture whose values can be asserted
        // rather than merely bounded -- a column misalignment would show here
        // and nowhere else.
        val halley = parseJplSbdbQuery(Fixtures.text("jpl_sbdb_comets.json")).firstOrNull { it.designation == "1P" }
        assertTrue(halley != null, "1P/Halley is missing from the capture")
        assertEquals("(Halley)", halley.name)
        assertEquals(0.967, halley.elements.eccentricity, 0.002)
        assertEquals(0.586, halley.elements.perihelionDistanceAu, 0.02)
        assertEquals(162.2, halley.elements.inclinationDeg, 0.5, "Halley is retrograde; a wrong column would not be")
    }
}
