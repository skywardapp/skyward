package dev.fritze.skyward.core.astro

import dev.fritze.skyward.core.model.CometElements
import dev.fritze.skyward.core.model.CometMagParams
import kotlin.math.abs
import kotlin.math.log10
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * §17.3b's *solver properties* — the half that has to hold on every platform
 * and needs no fixture: continuity through e = 1, convergence and its
 * iteration count, and the magnitude formula.
 *
 * The oracle half — daily JPL Horizons ephemerides for four comets, checked
 * for `r`, `delta`, RA/Dec and T-mag — is `KeplerHorizonsTest`, which lives
 * in `desktopTest` because it reads checked-in fixture resources (the same
 * reason `EclipseCanonFixtureTest` does).
 *
 * The elements below are Horizons' osculating solutions at each comet's
 * perihelion, from `jpl_horizons_comet_elements.csv`, spanning the four
 * regimes §17.3b names:
 *  - 2P/Encke:            e=0.85  (short-period elliptical; also q<0.5 au)
 *  - C/2020 F3 (NEOWISE): e=0.999 (near-parabolic, e<1)
 *  - 2I/Borisov:          e=3.36  (genuinely hyperbolic, interstellar)
 *  - 96P/Machholz 1:      e=0.96, q=0.124 au (deep perihelion)
 */
class KeplerTest {

    private data class CometFixture(
        val name: String,
        val eccentricity: Double,
        val perihelionDistanceAu: Double,
        val inclinationDeg: Double,
        val ascendingNodeDeg: Double,
        val argPerihelionDeg: Double,
        val tpIso: String,
    ) {
        fun elements() = CometElements(
            epoch = Instant.parse(tpIso), // these osculate at perihelion; the propagator only uses tp
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
        eccentricity = 0.8469329771283233,
        perihelionDistanceAu = 0.339596925978405,
        inclinationDeg = 11.33650906051411,
        ascendingNodeDeg = 334.0187283435793,
        argPerihelionDeg = 187.2883147078211,
        tpIso = "2023-10-22T12:40:43.977Z",
    )

    private val neowise = CometFixture(
        name = "C/2020 F3 (NEOWISE)",
        eccentricity = 0.9991782081129224,
        perihelionDistanceAu = 0.2946512466331692,
        inclinationDeg = 128.9375043020912,
        ascendingNodeDeg = 61.01042991771634,
        argPerihelionDeg = 37.2786526292898,
        tpIso = "2020-07-03T16:17:36.725Z",
    )

    private val borisov = CometFixture(
        name = "2I/Borisov (C/2019 Q4)",
        eccentricity = 3.356479398464173,
        perihelionDistanceAu = 2.006523258794727,
        inclinationDeg = 44.05261524551621,
        ascendingNodeDeg = 308.1477005805454,
        argPerihelionDeg = 209.1242889130816,
        tpIso = "2019-12-08T13:17:14.803Z",
    )

    private val machholz = CometFixture(
        name = "96P/Machholz 1",
        eccentricity = 0.9591612355150262,
        perihelionDistanceAu = 0.1239487663398483,
        inclinationDeg = 58.13767858350626,
        ascendingNodeDeg = 94.25428300134381,
        argPerihelionDeg = 14.79291982395155,
        tpIso = "2017-10-27T23:02:51.351Z",
    )

    private val allFixtures = listOf(encke, neowise, borisov, machholz)

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
    fun convergesWellInsideTheIterationCapAcrossEveryRegime() {
        // §17.3b: "converges in < 30 iterations for every
        // e in {0.1, 0.5, 0.9, 0.98, 0.999, 1.0, 1.001, 1.05, 3.0} across the
        // full range of dt". The *count* is the point — a solver that stops
        // at exactly the cap satisfies "it returned a position" and is one
        // step from silently dropping comets, which is what §7.4.2 does when
        // the solve fails.
        //
        // Perihelion distance is swept too, and that is not decoration: this
        // test previously fixed q at 1.0 au, which is precisely what hid the
        // bug the Horizons oracle later found. A Newton step here is f/f' and
        // f' bottoms out at q, so small-q orbits are where the iteration
        // struggles — 2P/Encke at q=0.34 au needed 26 iterations and failed
        // outright at dt = ±182 days.
        val eccentricities = listOf(0.1, 0.5, 0.9, 0.98, 0.999, 1.0, 1.001, 1.05, 3.0)
        val perihelionDistances = listOf(0.12, 0.34, 1.0, 3.0)
        val dtValues = listOf(-3650.0, -400.0, -182.0, -50.0, -1.0, 0.0, 1.0, 50.0, 182.0, 400.0, 3650.0)

        var worstIterations = 0
        var worstCase = ""
        for (e in eccentricities) {
            for (q in perihelionDistances) {
                for (dt in dtValues) {
                    val solution = solveUniversalAnomaly(q, e, dt)
                    assertNotNull(solution, "e=$e, q=$q au, dt=$dt days: no convergence")
                    assertTrue(
                        solution.iterations < 30,
                        "e=$e, q=$q au, dt=$dt days took ${solution.iterations} iterations",
                    )
                    if (solution.iterations > worstIterations) {
                        worstIterations = solution.iterations
                        worstCase = "e=$e, q=$q au, dt=$dt days"
                    }

                    val el = CometElements(
                        epoch = Instant.fromEpochMilliseconds(0),
                        eccentricity = e,
                        perihelionDistanceAu = q,
                        inclinationDeg = 0.0,
                        ascendingNodeDeg = 0.0,
                        argPerihelionDeg = 0.0,
                        tpPerihelion = Instant.fromEpochMilliseconds(0),
                    )
                    val position = heliocentricPosition(el, Instant.fromEpochMilliseconds((dt * 86400_000L).toLong()))
                    assertNotNull(position, "e=$e, q=$q au, dt=$dt days: no position")
                    assertTrue(position.length() > 0.0, "e=$e, q=$q au, dt=$dt days: expected a nonzero radius")
                }
            }
        }

        // A margin, not just a pass: everywhere an actual comet lives the
        // safeguarded solve converges in a handful of steps, and the worst
        // corner of this envelope — a steeply hyperbolic orbit a decade from
        // perihelion, where the Stumpff functions overflow and the solve
        // spends its first steps bisecting a very wide bracket — still lands
        // at 18. Asserting the margin, not just the cap, means a change that
        // pushes the solve toward 30 shows up here long before it starts
        // dropping comets in the field.
        assertTrue(
            worstIterations <= 20,
            "worst case ($worstCase) took $worstIterations iterations, expected a margin under §17.3b's 30",
        )
    }

    @Test
    fun convergesForNearParabolicOrbitsAcrossLongTimeSpans() {
        // CodeRabbit review (PR #1) questioned whether the Newton-Raphson
        // seed could fail to converge for near-parabolic orbits (alpha ~ 0)
        // combined with large |dt| — a combination the sweep above brackets
        // but does not sample right at the boundary, so exercise the envelope
        // where alpha is smallest.
        val eccentricities = listOf(0.9999, 0.99999, 1.0, 1.00001, 1.0001)
        val dtValues = listOf(-3650.0, -1000.0, 1000.0, 3650.0)
        for (e in eccentricities) {
            for (dt in dtValues) {
                val solution = solveUniversalAnomaly(1.0, e, dt)
                assertNotNull(solution, "e=$e, dt=$dt days should converge")
                assertTrue(solution.iterations < 30, "e=$e, dt=$dt days took ${solution.iterations} iterations")
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
        // JPL's fitted M1/K1 for 2P/Encke as of this fixture's capture.
        val magParams = CometMagParams(m1 = 15.6, k1 = 4.5)
        val mag = apparentMagnitude(encke.elements(), magParams, Instant.parse(encke.tpIso))
        assertNotNull(mag)
        // This broad range rejects non-finite values and gross magnitude
        // errors; it does not by itself distinguish a correct coefficient
        // from a subtly wrong one — see the regression test below for that,
        // and KeplerHorizonsTest for the comparison against Horizons' own
        // T-mag across a year.
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
        // the same position engine KeplerHorizonsTest validates against a
        // year of Horizons ephemerides.
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

    @Test
    fun aSolveThatCannotConvergeReturnsNullRatherThanAGarbagePosition() {
        // §7.4.2's contract: "if it fails to converge, drop the comet with a
        // diagnostic rather than emitting garbage". Degenerate elements — a
        // perihelion distance of zero — make the equation unsolvable, and the
        // answer must be null, not a position.
        val degenerate = CometElements(
            epoch = Instant.fromEpochMilliseconds(0),
            eccentricity = 1.0,
            perihelionDistanceAu = 0.0,
            inclinationDeg = 0.0,
            ascendingNodeDeg = 0.0,
            argPerihelionDeg = 0.0,
            tpPerihelion = Instant.fromEpochMilliseconds(0),
        )
        val t = Instant.fromEpochMilliseconds(50L * 86400_000L)
        assertEquals(null, heliocentricPosition(degenerate, t))
        assertEquals(null, apparentMagnitude(degenerate, CometMagParams(m1 = 10.0, k1 = 4.5), t))
    }
}
