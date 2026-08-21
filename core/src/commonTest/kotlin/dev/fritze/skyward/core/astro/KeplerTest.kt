package dev.fritze.skyward.core.astro

import dev.fritze.skyward.core.model.CometElements
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The universal-variable Kepler propagator's fixture-free properties:
 * convergence across the full eccentricity range and time spans, and
 * continuity of r(e) through the parabolic boundary. None of these need
 * captured data, so they stay in `commonTest` and run on every target.
 *
 * Agreement with real ephemerides -- §17.3b's actual accuracy claim -- is
 * `desktopTest`'s [dev.fritze.skyward.core.astro.KeplerHorizonsFixtureTest],
 * which reads the checked-in JPL Horizons capture
 * (`fixtures/jpl_horizons_comet_vectors.json`).
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
}
