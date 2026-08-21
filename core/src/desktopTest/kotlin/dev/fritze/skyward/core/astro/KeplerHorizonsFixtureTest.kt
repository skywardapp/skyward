package dev.fritze.skyward.core.astro

import dev.fritze.skyward.core.model.CometElements
import dev.fritze.skyward.core.model.CometMagParams
import dev.fritze.skyward.core.testing.Fixtures
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * §17.3b: validates the universal-variable Kepler propagator against real JPL
 * Horizons heliocentric-ecliptic-J2000 state vectors, captured into
 * `fixtures/jpl_horizons_comet_vectors.json` by
 * `tools/fixtures/fetch-horizons.py` (elements from
 * https://ssd-api.jpl.nasa.gov/sbdb.api, vectors from
 * https://ssd.jpl.nasa.gov/api/horizons.api with CENTER=500@10,
 * REF_PLANE=ECLIPTIC, REF_SYSTEM=J2000, OUT_UNITS=AU-D).
 *
 * Four comets span the eccentricity/perihelion regimes §17.3b calls for:
 *  - 2P/Encke:            e=0.85  (short-period elliptical; also q<0.5 au)
 *  - C/2020 F3 (NEOWISE): e=0.999 (near-parabolic, e<1)
 *  - 2I/Borisov:          e=3.36  (genuinely hyperbolic, interstellar)
 *  - 96P/Machholz 1:      e=0.96, q=0.124 au (deep perihelion)
 *
 * Horizons' vectors include planetary perturbations and (for some of these
 * comets) fitted non-gravitational forces; this propagator is intentionally
 * two-body-only (§7.4.2's documented limitation). Tolerances below are
 * therefore generous -- sized to catch a broken transformation (wrong axis,
 * sign error, wrong conic branch) rather than to validate high-precision
 * agreement, consistent with the design doc's own "well under a degree"
 * framing for what this propagator promises.
 *
 * The propagator's fixture-free properties (convergence across the full
 * eccentricity range, continuity through e=1) stay in `commonTest`'s
 * `KeplerTest`, which needs no captured data and so runs on every target.
 */
class KeplerHorizonsFixtureTest {

    @Serializable
    private data class CapturedPoint(val time: String, val x: Double, val y: Double, val z: Double)

    @Serializable
    private data class CapturedComet(
        val name: String,
        val eccentricity: Double,
        val perihelionDistanceAu: Double,
        val inclinationDeg: Double,
        val ascendingNodeDeg: Double,
        val argPerihelionDeg: Double,
        val tp: String,
        val points: List<CapturedPoint>,
    ) {
        fun elements() = CometElements(
            epoch = Instant.parse(tp), // epoch isn't used by the propagator; tp is what matters
            eccentricity = eccentricity,
            perihelionDistanceAu = perihelionDistanceAu,
            inclinationDeg = inclinationDeg,
            ascendingNodeDeg = ascendingNodeDeg,
            argPerihelionDeg = argPerihelionDeg,
            tpPerihelion = Instant.parse(tp),
        )
    }

    @Serializable
    private data class Capture(val comets: List<CapturedComet>)

    private val json = Json { ignoreUnknownKeys = true }
    private val comets: List<CapturedComet> =
        json.decodeFromString(Capture.serializer(), Fixtures.text("jpl_horizons_comet_vectors.json")).comets

    private val encke: CapturedComet get() = comets.first { it.name.startsWith("2P/") }

    @Test
    fun theCaptureStillCoversEveryEccentricityRegime() {
        // A refresh that quietly lost a comet would leave the tolerance checks
        // below passing on a narrower set than §17.3b asks for.
        assertEquals(4, comets.size, "expected four comets in the capture, found ${comets.map { it.name }}")
        assertTrue(comets.any { it.eccentricity < 0.9 }, "no short-period elliptical comet in the capture")
        assertTrue(comets.any { it.eccentricity in 0.99..1.0 }, "no near-parabolic comet in the capture")
        assertTrue(comets.any { it.eccentricity > 1.0 }, "no hyperbolic comet in the capture")
        assertTrue(comets.any { it.perihelionDistanceAu < 0.15 }, "no deep-perihelion comet in the capture")
        assertTrue(comets.all { it.points.size >= 5 }, "every comet needs several samples across its perihelion passage")
    }

    @Test
    fun heliocentricPositionMatchesHorizonsWithinTwoBodyTolerance() {
        for (comet in comets) {
            val elements = comet.elements()
            for (point in comet.points) {
                val t = Instant.parse(point.time)
                val result = heliocentricPosition(elements, t)
                assertNotNull(result, "${comet.name} at ${point.time}: propagator failed to converge")

                val dx = result.x - point.x
                val dy = result.y - point.y
                val dz = result.z - point.z
                val errorAu = sqrt(dx * dx + dy * dy + dz * dz)
                val referenceR = sqrt(point.x.pow(2) + point.y.pow(2) + point.z.pow(2))

                // Two-body propagation vs. a perturbed+non-gravitational Horizons
                // solution: allow the larger of a fixed floor and 5% of distance
                // from the Sun, growing so this doesn't become flaky right at
                // perihelion where an absolute-AU error is a larger fraction.
                val tolerance = maxOf(0.05, 0.05 * referenceR)
                assertTrue(
                    errorAu < tolerance,
                    "${comet.name} at ${point.time}: position error $errorAu au exceeds tolerance $tolerance au " +
                        "(got (${result.x}, ${result.y}, ${result.z}), expected (${point.x}, ${point.y}, ${point.z}))",
                )
            }
        }
    }

    @Test
    fun perihelionRadiusEqualsQAtTimeOfPerihelionPassage() {
        for (comet in comets) {
            val elements = comet.elements()
            val pos = heliocentricPosition(elements, elements.tpPerihelion)
            assertNotNull(pos, "${comet.name}: expected convergence exactly at perihelion")
            assertTrue(
                abs(pos.length() - comet.perihelionDistanceAu) < 1e-6,
                "${comet.name}: expected r == q (${comet.perihelionDistanceAu}) at tp, got ${pos.length()}",
            )
        }
    }

    @Test
    fun apparentMagnitudeIsFiniteAndPlausibleNearPerihelion() {
        val mag = apparentMagnitude(encke.elements(), ENCKE_MAG_PARAMS, Instant.parse(encke.tp))
        assertNotNull(mag)
        // This broad range rejects non-finite values and gross magnitude
        // errors; it does not by itself distinguish a correct coefficient
        // from a subtly wrong one -- see the regression test below for that.
        assertTrue(mag in -5.0..25.0, "expected a plausible apparent magnitude, got $mag")
    }

    @Test
    fun apparentMagnitudeUsesTheDirectK1CoefficientNotMpcs2Point5nConvention() {
        // Regression for docs/adr/0004-comet-magnitude-formula.md: the broad
        // plausibility band above can't tell `m1 + K1*log10(r)` (correct, per
        // JPL SBDB) apart from the obsolete `m1 + 2.5*K1*log10(r)` this app
        // used to compute. `expected` is written out literally here -- not by
        // calling apparentMagnitude() -- so a regression to the 2.5x form
        // would change apparentMagnitude()'s output without changing this
        // expectation, and the test would fail.
        val t = Instant.parse(encke.tp)
        val elements = encke.elements()

        // r == q exactly at perihelion passage (see
        // perihelionRadiusEqualsQAtTimeOfPerihelionPassage); delta comes from
        // the same position engine the fixtures above validate against
        // Horizons.
        val r = encke.perihelionDistanceAu
        val cometHelio = heliocentricPosition(elements, t)
        assertNotNull(cometHelio)
        val earthHelio = earthHeliocentricPositionEcliptic(t)
        val delta = (cometHelio - earthHelio).length()

        val expected = ENCKE_MAG_PARAMS.m1 + 5.0 * log10(delta) + ENCKE_MAG_PARAMS.k1 * log10(r)
        val actual = apparentMagnitude(elements, ENCKE_MAG_PARAMS, t)
        assertNotNull(actual)
        assertEquals(expected, actual, 1e-9)

        val obsoleteDoubleCountedForm = ENCKE_MAG_PARAMS.m1 + 5.0 * log10(delta) + 2.5 * ENCKE_MAG_PARAMS.k1 * log10(r)
        assertTrue(
            abs(obsoleteDoubleCountedForm - actual) > 1.0,
            "expected the corrected formula to differ meaningfully from the obsolete 2.5*K1 form",
        )
    }

    private companion object {
        // JPL SBDB's fitted M1/K1 for 2P/Encke. Not read from the fixture:
        // these tests are about the magnitude *formula*, and pinning the
        // coefficients keeps a refreshed fit from quietly moving the
        // expectation the regression test exists to hold still.
        val ENCKE_MAG_PARAMS = CometMagParams(m1 = 15.6, k1 = 4.5)
    }
}
