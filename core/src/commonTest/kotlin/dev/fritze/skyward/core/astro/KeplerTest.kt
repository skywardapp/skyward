package dev.fritze.skyward.core.astro

import dev.fritze.skyward.core.model.CometElements
import dev.fritze.skyward.core.model.CometMagParams
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The universal-variable Kepler propagator's fixture-free properties:
 * convergence (including the iteration *count*, not just that a position
 * came back) across the full eccentricity range and time spans, continuity
 * of r(e) through the parabolic boundary, and the guarantee that unsolvable
 * elements return null rather than a garbage position. None of these need
 * captured data, so they stay in `commonTest` and run on every target.
 *
 * Agreement with real ephemerides -- §17.3b's actual accuracy claim -- is
 * `desktopTest`'s [dev.fritze.skyward.core.astro.KeplerHorizonsFixtureTest],
 * which reads the checked-in JPL Horizons capture.
 */
class KeplerTest {

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
        // Perihelion distance is swept too, and that is not decoration: an
        // earlier version of this test fixed q at 1.0 au, which is precisely
        // what hid a real convergence bug (see Kepler.kt's solveUniversalAnomaly
        // KDoc). A Newton step here is f/f' and f' bottoms out at q, so
        // small-q orbits are where the iteration struggles — 2P/Encke at
        // q=0.34 au needed 26 iterations and failed outright at
        // dt = ±182 days before the safeguarded solve replaced plain Newton.
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
