package dev.fritze.skyward.core.astro

import dev.fritze.skyward.core.model.CometElements
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt
import dev.fritze.skyward.core.model.CometMagParams
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * §17.3b: validates the universal-variable Kepler propagator against real
 * JPL Horizons heliocentric-ecliptic-J2000 state vectors (position only used
 * here), captured 2026-08-13 via https://ssd.jpl.nasa.gov/api/horizons.api
 * (CENTER=500@10, REF_PLANE=ECLIPTIC, REF_SYSTEM=J2000, OUT_UNITS=AU-D) for
 * osculating elements pulled from https://ssd-api.jpl.nasa.gov/sbdb.api at
 * the same time — both real network fetches, not fabricated numbers.
 *
 * Four comets span the eccentricity/perihelion regimes §17.3b calls for:
 *  - 2P/Encke:            e=0.85  (short-period elliptical; also q<0.5 au)
 *  - C/2020 F3 (NEOWISE):  e=0.999 (near-parabolic, e<1)
 *  - 2I/Borisov:           e=3.36  (genuinely hyperbolic, interstellar)
 *  - 96P/Machholz 1:       e=0.96, q=0.124 au (deep perihelion)
 *
 * Horizons' vectors include planetary perturbations and (for some of these
 * comets) fitted non-gravitational forces; this propagator is intentionally
 * two-body-only (§7.4.2's documented limitation). Tolerances below are
 * therefore generous — sized to catch a broken transformation (wrong axis,
 * sign error, wrong conic branch) rather than to validate high-precision
 * agreement, consistent with the design doc's own "well under a degree"
 * framing for what this propagator promises.
 */
class KeplerTest {

    private data class FixturePoint(val isoTime: String, val x: Double, val y: Double, val z: Double)

    private data class CometFixture(
        val name: String,
        val eccentricity: Double,
        val perihelionDistanceAu: Double,
        val inclinationDeg: Double,
        val ascendingNodeDeg: Double,
        val argPerihelionDeg: Double,
        val tpIso: String,
        val points: List<FixturePoint>,
    ) {
        fun elements() = CometElements(
            epoch = Instant.parse(tpIso), // epoch isn't used by the propagator; tp is what matters
            eccentricity = eccentricity,
            perihelionDistanceAu = perihelionDistanceAu,
            inclinationDeg = inclinationDeg,
            ascendingNodeDeg = ascendingNodeDeg,
            argPerihelionDeg = argPerihelionDeg,
            tpPerihelion = Instant.parse(tpIso),
        )
    }

    private val encke = CometFixture(
        name = "2P/Encke",
        eccentricity = 0.8477496967533629,
        perihelionDistanceAu = 0.3379482792219925,
        inclinationDeg = 11.41227811179314,
        ascendingNodeDeg = 334.1935846036774,
        argPerihelionDeg = 187.1342463695676,
        tpIso = "2023-10-22T03:35:18.402Z",
        points = listOf(
            FixturePoint("2023-08-23T00:00:00Z", 1.051701017972376, 0.7013602295917165, 0.2187653346725950),
            FixturePoint("2023-09-22T00:00:00Z", 0.4303234388816862, 0.6525589021123415, 0.1553985424999622),
            FixturePoint("2023-10-22T00:00:00Z", -0.3143660728723623, 0.1291788437223067, -0.004329011446828872),
            FixturePoint("2023-11-21T00:00:00Z", -0.06776443881092890, -0.7617979892757099, -0.1432432699819468),
            FixturePoint("2023-12-21T00:00:00Z", 0.3977194246042342, -1.189867546940854, -0.1795074298587300),
            FixturePoint("2024-01-20T00:00:00Z", 0.8161073078634726, -1.448140804613395, -0.1893064592516360),
        ),
    )

    private val neowise = CometFixture(
        name = "C/2020 F3 (NEOWISE)",
        eccentricity = 0.9991780262531292,
        perihelionDistanceAu = 0.2946512493809196,
        inclinationDeg = 128.9375027594809,
        ascendingNodeDeg = 61.01042818536988,
        argPerihelionDeg = 37.2786584481257,
        tpIso = "2020-07-03T16:17:36.791Z",
        points = listOf(
            FixturePoint("2020-06-03T00:00:00Z", -0.3261993807300482, 0.4921771139992217, -0.6483605267927859),
            FixturePoint("2020-07-03T00:00:00Z", 0.2068011411352132, 0.1737864130402836, 0.1196378546172430),
            FixturePoint("2020-08-02T00:00:00Z", -0.07012294886956955, -0.7561372171604207, 0.3776384273867799),
            FixturePoint("2020-09-01T00:00:00Z", -0.4421092570703278, -1.328300464294027, 0.3181263162429361),
            FixturePoint("2020-10-01T00:00:00Z", -0.7748515257874168, -1.770233597716657, 0.2229777226240595),
        ),
    )

    private val borisov = CometFixture(
        name = "2I/Borisov (C/2019 Q4)",
        eccentricity = 3.356475782676596,
        perihelionDistanceAu = 2.006520878500843,
        inclinationDeg = 44.05264247909138,
        ascendingNodeDeg = 308.1477292269942,
        argPerihelionDeg = 209.1236864378081,
        tpIso = "2019-12-08T13:16:05.886Z",
        points = listOf(
            FixturePoint("2019-10-01T00:00:00Z", -1.183498147724817, 2.181864619913563, 0.4034184544782334),
            FixturePoint("2019-10-31T00:00:00Z", -1.405096195817254, 1.667082794831075, -0.07280826079003846),
            FixturePoint("2019-11-30T00:00:00Z", -1.590642116396372, 1.110649584621876, -0.5464982089535998),
            FixturePoint("2019-12-30T00:00:00Z", -1.725801404318127, 0.5193502160916664, -1.002689415012832),
            FixturePoint("2020-01-29T00:00:00Z", -1.809783099773198, -0.08800549418435584, -1.429538498950920),
        ),
    )

    private val machholz = CometFixture(
        name = "96P/Machholz 1",
        eccentricity = 0.9591608428638698,
        perihelionDistanceAu = 0.1239496432868665,
        inclinationDeg = 58.13790031260349,
        ascendingNodeDeg = 94.25472309284206,
        argPerihelionDeg = 14.7928448266822,
        tpIso = "2017-10-27T23:03:31.543Z",
        points = listOf(
            FixturePoint("2017-09-27T00:00:00Z", 0.4400616268413501, -0.5145959756336481, -0.6446534825230043),
            FixturePoint("2017-10-27T00:00:00Z", 0.007665872786246913, 0.1289396704194408, -0.02768941098053810),
            FixturePoint("2017-11-26T00:00:00Z", -0.1451279711845315, -0.8174141378786975, 0.3304188671839806),
            FixturePoint("2017-12-26T00:00:00Z", -0.09246615643197419, -1.419954526859883, 0.3178340138573471),
            FixturePoint("2018-01-25T00:00:00Z", -0.02952061351187763, -1.891522727933946, 0.2731101864290384),
        ),
    )

    private val allFixtures = listOf(encke, neowise, borisov, machholz)

    @Test
    fun heliocentricPositionMatchesHorizonsWithinTwoBodyTolerance() {
        for (fixture in allFixtures) {
            val elements = fixture.elements()
            for (point in fixture.points) {
                val t = Instant.parse(point.isoTime)
                val result = heliocentricPosition(elements, t)
                assertNotNull(result, "${fixture.name} at ${point.isoTime}: propagator failed to converge")

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
                    "${fixture.name} at ${point.isoTime}: position error $errorAu au exceeds tolerance $tolerance au " +
                        "(got (${result.x}, ${result.y}, ${result.z}), expected (${point.x}, ${point.y}, ${point.z}))",
                )
            }
        }
    }

    @Test
    fun rVariesContinuouslyAcrossParabolicBoundary() {
        // §17.3b: the specific failure a branched (ellipse/parabola/hyperbola)
        // implementation would exhibit — a step discontinuity in r(e) as e
        // sweeps through 1.0 at fixed q and dt. Sampled densely right around
        // e=1 (not a coarse grid — a coarse grid changes r noticeably just
        // from normal continuous variation, which isn't what this checks for).
        val q = 1.0
        val dtDays = 50.0
        val eccentricities = (-10..10).map { 1.0 + it * 1e-7 }
        val radii = eccentricities.map { e ->
            val el = CometElements(
                epoch = Instant.fromEpochMilliseconds(0),
                eccentricity = e,
                perihelionDistanceAu = q,
                inclinationDeg = 0.0,
                ascendingNodeDeg = 0.0,
                argPerihelionDeg = 0.0,
                tpPerihelion = Instant.fromEpochMilliseconds(0),
            )
            val t = Instant.fromEpochMilliseconds((dtDays * 86400_000L).toLong())
            val pos = heliocentricPosition(el, t)
            assertNotNull(pos, "e=$e should converge")
            pos.length()
        }
        for (i in 1 until radii.size) {
            val jump = abs(radii[i] - radii[i - 1])
            assertTrue(jump < 1e-6, "r(e) jumped by $jump au between e=${eccentricities[i - 1]} and e=${eccentricities[i]}")
        }
    }

    @Test
    fun convergesAcrossTheFullEccentricityRangeAndTimeSpans() {
        val eccentricities = listOf(0.1, 0.5, 0.9, 0.98, 0.999, 1.0, 1.001, 1.05, 3.0)
        val dtValues = listOf(-400.0, -50.0, -1.0, 0.0, 1.0, 50.0, 400.0)
        for (e in eccentricities) {
            val el = CometElements(
                epoch = Instant.fromEpochMilliseconds(0),
                eccentricity = e,
                perihelionDistanceAu = 1.0,
                inclinationDeg = 0.0,
                ascendingNodeDeg = 0.0,
                argPerihelionDeg = 0.0,
                tpPerihelion = Instant.fromEpochMilliseconds(0),
            )
            for (dt in dtValues) {
                val t = Instant.fromEpochMilliseconds((dt * 86400_000L).toLong())
                val pos = heliocentricPosition(el, t)
                assertNotNull(pos, "e=$e, dt=$dt days should converge")
                assertTrue(pos.length() > 0.0, "e=$e, dt=$dt days: expected a nonzero radius")
            }
        }
    }

    @Test
    fun convergesForNearParabolicOrbitsAcrossLongTimeSpans() {
        // CodeRabbit review (PR #1) questioned whether the Newton-Raphson
        // seed (`GAUSS_K * dt * sqrt(abs(alpha))`) could fail to converge for
        // near-parabolic orbits (alpha ~ 0) combined with large |dt| — a
        // combination convergesAcrossTheFullEccentricityRangeAndTimeSpans
        // doesn't stress (that test's dt tops out at 400 days, which the
        // app's own ~3-year default query horizon already exceeds) — so
        // exercise a much wider envelope here, years rather than months, to
        // confirm the existing seed has real margin rather than assuming it.
        val eccentricities = listOf(0.9999, 0.99999, 1.0, 1.00001, 1.0001)
        val dtValues = listOf(-3650.0, -1000.0, 1000.0, 3650.0)
        for (e in eccentricities) {
            val el = CometElements(
                epoch = Instant.fromEpochMilliseconds(0),
                eccentricity = e,
                perihelionDistanceAu = 1.0,
                inclinationDeg = 0.0,
                ascendingNodeDeg = 0.0,
                argPerihelionDeg = 0.0,
                tpPerihelion = Instant.fromEpochMilliseconds(0),
            )
            for (dt in dtValues) {
                val t = Instant.fromEpochMilliseconds((dt * 86400_000L).toLong())
                val pos = heliocentricPosition(el, t)
                assertNotNull(pos, "e=$e, dt=$dt days should converge")
                assertTrue(pos.length() > 0.0, "e=$e, dt=$dt days: expected a nonzero radius")
            }
        }
    }

    @Test
    fun perihelionRadiusEqualsQAtTimeOfPerihelionPassage() {
        for (fixture in allFixtures) {
            val elements = fixture.elements()
            val pos = heliocentricPosition(elements, elements.tpPerihelion)
            assertNotNull(pos, "${fixture.name}: expected convergence exactly at perihelion")
            assertTrue(
                abs(pos.length() - fixture.perihelionDistanceAu) < 1e-6,
                "${fixture.name}: expected r == q (${fixture.perihelionDistanceAu}) at tp, got ${pos.length()}",
            )
        }
    }

    @Test
    fun apparentMagnitudeIsFiniteAndPlausibleNearPerihelion() {
        // JPL SBDB's fitted M1/K1 for 2P/Encke as of this fixture's capture.
        val magParams = CometMagParams(m1 = 15.6, k1 = 4.5)
        val mag = apparentMagnitude(encke.elements(), magParams, Instant.parse(encke.tpIso))
        assertNotNull(mag)
        // This broad range rejects non-finite values and gross magnitude
        // errors; it does not by itself distinguish a correct coefficient
        // from a subtly wrong one — see the regression test below for that.
        assertTrue(mag in -5.0..25.0, "expected a plausible apparent magnitude, got $mag")
    }

    @Test
    fun apparentMagnitudeUsesTheDirectK1CoefficientNotMpcs2Point5nConvention() {
        // Regression for docs/adr/0004-comet-magnitude-formula.md: the broad
        // plausibility band above can't tell `m1 + K1*log10(r)` (correct, per
        // JPL SBDB) apart from the obsolete `m1 + 2.5*K1*log10(r)` this app
        // used to compute. `expected` is written out literally here — not by
        // calling apparentMagnitude() — so a regression to the 2.5x form
        // would change apparentMagnitude()'s output without changing this
        // expectation, and the test would fail.
        val magParams = CometMagParams(m1 = 15.6, k1 = 4.5)
        val t = Instant.parse(encke.tpIso)
        val elements = encke.elements()

        // r == q exactly at perihelion passage (see
        // perihelionRadiusEqualsQAtTimeOfPerihelionPassage); delta comes from
        // the same position engine the KeplerTest fixtures above validate
        // against live Horizons data.
        val r = encke.perihelionDistanceAu
        val cometHelio = heliocentricPosition(elements, t)
        assertNotNull(cometHelio)
        val earthHelio = earthHeliocentricPositionEcliptic(t)
        val delta = (cometHelio - earthHelio).length()

        val expected = magParams.m1 + 5.0 * log10(delta) + magParams.k1 * log10(r)
        val actual = apparentMagnitude(elements, magParams, t)
        assertNotNull(actual)
        assertEquals(expected, actual, 1e-9)

        val obsoleteDoubleCountedForm = magParams.m1 + 5.0 * log10(delta) + 2.5 * magParams.k1 * log10(r)
        assertTrue(
            abs(obsoleteDoubleCountedForm - actual) > 1.0,
            "expected the corrected formula to differ meaningfully from the obsolete 2.5*K1 form",
        )
    }
}
