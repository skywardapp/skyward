package dev.fritze.skyward.core.astro

import dev.fritze.skyward.core.model.CometElements
import dev.fritze.skyward.core.model.CometMagParams
import io.github.cosinekitty.astronomy.Body
import io.github.cosinekitty.astronomy.helioVector
import io.github.cosinekitty.astronomy.rotationEqjEcl
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.cosh
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.sqrt
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/** Gaussian gravitational constant, rad/day, AU^(3/2) — sqrt(GM_sun) in these units (§7.4.2). */
const val GAUSS_K = 0.01720209895

/** Newton-Raphson tolerance and iteration cap for the universal Kepler equation solve (§7.4.2). */
private const val KEPLER_TOLERANCE = 1e-11
private const val KEPLER_MAX_ITERATIONS = 30

/**
 * Stumpff functions c2(z), c3(z). Uses the series expansion for `|z| < 1e-6`
 * to avoid the 0/0 that the closed forms hit at z=0 (§7.4.2).
 */
internal fun stumpff(z: Double): Pair<Double, Double> = when {
    z > 1e-6 -> {
        val s = sqrt(z)
        (1 - cos(s)) / z to (s - sin(s)) / (s * s * s)
    }
    z < -1e-6 -> {
        val s = sqrt(-z)
        (1 - cosh(s)) / z to (sinh(s) - s) / (s * s * s)
    }
    else -> (0.5 - z / 24.0) to (1.0 / 6.0 - z / 120.0)
}

/**
 * Heliocentric position, J2000 ecliptic, AU — valid for any eccentricity via
 * the universal-variable (Stumpff) formulation (§7.4.2), which avoids the
 * per-conic branching (and the discontinuities at e=1 it causes) that a
 * Newton-Raphson-on-Kepler's-equation / Barker's-equation split would have.
 *
 * Returns `null` if the Newton-Raphson solve fails to converge within
 * [KEPLER_MAX_ITERATIONS] — callers must drop the comet with a diagnostic
 * rather than propagate a garbage position (§7.4.2 step 2), which is why
 * this is nullable rather than the non-null signature the design doc's
 * pseudocode shows.
 */
fun heliocentricPosition(el: CometElements, t: Instant): Vec3? {
    val dt = (t - el.tpPerihelion).inWholeSeconds / 86400.0 // days since perihelion passage
    val q = el.perihelionDistanceAu
    val e = el.eccentricity
    // alpha = 1/a: positive for ellipses, 0 for parabolas, negative for hyperbolas — no branch.
    val alpha = (1.0 - e) / q

    // Universal Kepler's equation, referenced to perihelion passage (r0 = q,
    // radial velocity = 0 at perihelion, which is what collapses the general
    // state-vector form of the equation down to just x, q, alpha, and dt):
    //   f(x) = (1 - alpha*q) x^3 c3(z) + q*x - GAUSS_K*dt = 0,   z = alpha*x^2
    //   f'(x) = (1 - alpha*q) x^2 c2(z) + q                      (this is also r(x), the radius)
    fun f(x: Double): Pair<Double, Double> {
        val z = alpha * x * x
        val (c2, c3) = stumpff(z)
        val oneMinusAlphaQ = 1.0 - alpha * q
        val value = oneMinusAlphaQ * x * x * x * c3 + q * x - GAUSS_K * dt
        val derivative = oneMinusAlphaQ * x * x * c2 + q
        return value to derivative
    }

    // Seed: exactly one Newton-Raphson step from x=0, using f(0) = -GAUSS_K*dt
    // and f'(0) = q (both independent of alpha, since z=alpha*x^2=0 there) —
    // i.e. x = 0 - f(0)/f'(0) = GAUSS_K*dt/q. Dimensionally consistent (AU^(1/2))
    // and, unlike a per-conic-branch seed, needs no special case for alpha's
    // sign or magnitude: it's derived from the equation itself, not a
    // conic-specific approximation, so it holds equally for elliptical,
    // parabolic, and hyperbolic orbits. A prior `GAUSS_K*dt*sqrt(abs(alpha))`
    // seed (dimensionally AU, not AU^(1/2)) failed to converge for
    // near-parabolic orbits (alpha ~ 0) at large |dt| — see
    // KeplerTest.convergesForNearParabolicOrbitsAcrossLongTimeSpans.
    var x = GAUSS_K * dt / q

    var converged = false
    for (iteration in 0 until KEPLER_MAX_ITERATIONS) {
        val (value, derivative) = f(x)
        if (abs(value) < KEPLER_TOLERANCE) {
            converged = true
            break
        }
        if (derivative == 0.0) return null
        val step = value / derivative
        x -= step
        // A vanishing Newton step is an equally valid convergence signal as a
        // small residual: f(x) sums terms of very different magnitude (the
        // Stumpff cubic term vs. GAUSS_K*dt), so for some regimes (observed:
        // near-parabolic orbits at large |dt|, i.e. alpha ~ 0 with dt in the
        // thousands of days — see
        // KeplerTest.convergesForNearParabolicOrbitsAcrossLongTimeSpans)
        // floating-point cancellation keeps |f(x)| a couple of ULPs above
        // KEPLER_TOLERANCE forever even once x itself has stopped moving.
        if (abs(step) < 1e-12 * maxOf(1.0, abs(x))) {
            converged = true
            break
        }
    }
    if (!converged) return null

    val z = alpha * x * x
    val (c2, c3) = stumpff(z)
    val oneMinusAlphaQ = 1.0 - alpha * q
    val r = oneMinusAlphaQ * x * x * c2 + q // radius at the solution == f'(x)

    // Perifocal-frame (X, Y) via the f/g functions, evaluated at perihelion
    // (r0 = q along the periapsis axis, v0 purely tangential):
    //   f = 1 - (x^2/q) c2(z),   g = dt - (x^3/GAUSS_K) c3(z)
    //   X = f*q  (== r*cos(nu)),   Y = g*v_peri  (== r*sin(nu))
    val fCoef = 1.0 - (x * x / q) * c2
    val gCoef = dt - (x * x * x / GAUSS_K) * c3
    val vPeri = GAUSS_K * sqrt((1.0 + e) / q) // speed at perihelion
    val perifocalX = fCoef * q // == r * cos(nu)
    val perifocalY = gCoef * vPeri // == r * sin(nu)

    // Sanity check the two independent routes to r agree (defends the "no
    // garbage output" contract as cheaply as a single extra sqrt).
    val rFromXy = sqrt(perifocalX * perifocalX + perifocalY * perifocalY)
    if (abs(rFromXy - r) > 1e-6 * (1.0 + r)) return null

    // Rotate perifocal -> J2000 ecliptic via the standard P/Q vector
    // formulation (Meeus, "Astronomical Algorithms" ch. 33) — equivalent to
    // the design doc's Rz(-Omega)*Rx(-i)*Rz(-w) sequence, expressed without
    // an explicit 3x3 matrix multiply to avoid an active/passive rotation
    // sign ambiguity in a hand-rolled implementation of that sequence.
    val omega = el.ascendingNodeDeg.toRadians()
    val i = el.inclinationDeg.toRadians()
    val w = el.argPerihelionDeg.toRadians()

    val cosO = cos(omega); val sinO = sin(omega)
    val cosI = cos(i); val sinI = sin(i)
    val cosW = cos(w); val sinW = sin(w)

    val px = cosW * cosO - sinW * sinO * cosI
    val py = cosW * sinO + sinW * cosO * cosI
    val pz = sinW * sinI

    val qx = -sinW * cosO - cosW * sinO * cosI
    val qy = -sinW * sinO + cosW * cosO * cosI
    val qz = cosW * sinI

    return Vec3(
        perifocalX * px + perifocalY * qx,
        perifocalX * py + perifocalY * qy,
        perifocalX * pz + perifocalY * qz,
    )
}

/**
 * Earth's heliocentric position, J2000 ecliptic, AU — Astronomy Engine's
 * `helioVector` returns J2000 equatorial (EQJ); rotated here to match the
 * ecliptic frame [heliocentricPosition] works in.
 */
fun earthHeliocentricPositionEcliptic(t: Instant): Vec3 {
    val eqj = helioVector(Body.Earth, t.toAstroTime())
    val ecl = rotationEqjEcl().rotate(eqj)
    return Vec3(ecl.x, ecl.y, ecl.z)
}

/**
 * Predicted apparent magnitude: `m = M1 + 5*log10(delta) + K1*log10(r)`.
 * §7.4.2 states this as `2.5*K1*log10(r)`, but JPL SBDB's `K1` is already
 * the full slope term (`K1 = 2.5*n` in the MPC "M1, K1" comet-magnitude
 * convention this data comes from) — applying an extra `2.5*` double-counts
 * it. See docs/adr/0004-comet-magnitude-formula.md. Returns `null` if the
 * propagator can't converge on a position for [t] (see [heliocentricPosition]).
 */
fun apparentMagnitude(el: CometElements, mp: CometMagParams, t: Instant): Double? {
    val cometHelio = heliocentricPosition(el, t) ?: return null
    val earthHelio = earthHeliocentricPositionEcliptic(t)
    val r = cometHelio.length() // comet-Sun distance, AU
    val delta = (cometHelio - earthHelio).length() // comet-Earth distance, AU
    return mp.m1 + 5 * log10(delta) + mp.k1 * log10(r)
}

private fun Double.toRadians() = this * kotlin.math.PI / 180.0
