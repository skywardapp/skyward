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
 * The universal anomaly `x` solving §7.4.2's universal Kepler equation, with
 * the iteration count that found it.
 *
 * Split out of [heliocentricPosition] so §17.3b's "converges in < 30
 * iterations" can be asserted as the count it is, rather than inferred from
 * the solver merely not giving up — which is all a non-null position tells
 * you, and which would still hold at exactly the 30-iteration cap.
 */
internal data class UniversalAnomaly(val x: Double, val iterations: Int)

/**
 * Solves the universal Kepler equation, referenced to perihelion passage.
 * Null if it does not converge within [KEPLER_MAX_ITERATIONS].
 *
 * Safeguarded Newton-Raphson (Newton where it behaves, bisection where it
 * doesn't), which the equation's own shape makes both possible and cheap:
 *
 *   f(x)  = (1 - alpha*q) x^3 c3(z) + q*x - GAUSS_K*dt = 0,   z = alpha*x^2
 *   f'(x) = (1 - alpha*q) x^2 c2(z) + q
 *
 * `f'(x)` is the heliocentric radius at `x`, so it is strictly positive for
 * every conic: `f` is strictly increasing and the root is unique. That also
 * hands us a bracket for free. Since `f'(x) >= q` everywhere,
 * `f(x) >= q*x - GAUSS_K*dt`, so `x = GAUSS_K*dt/q` already has `f >= 0` —
 * it is a *bound*, not just a guess, and `[0, GAUSS_K*dt/q]` (reversed for
 * negative dt) brackets the root with no search.
 *
 * Plain Newton from that bound is what this used to do, and it is not
 * enough. `f'` oscillates between `q` and `2(1 - alpha*q)/alpha + q` along an
 * elliptical orbit, and where it touches the bottom of that range a Newton
 * step is `f/q` — huge when `q` is small. For 2P/Encke (q = 0.34 au) the
 * bound overshoots the root by a factor of six and the iteration needs 26
 * steps near perihelion and fails outright at dt = +-182 days, where
 * §7.4.2's contract then drops the comet with a diagnostic: a real comet
 * silently missing from a scan that routinely reaches years either side of
 * perihelion (§7.4.3). Bisecting whenever a Newton step would leave the
 * bracket, or is not at least halving it, removes that failure mode without
 * costing anything in the well-behaved case: Encke now needs 6 iterations at
 * worst across that same year, and §17.3b's whole eccentricity sweep tops
 * out at 18 — in its most extreme corner, a steeply hyperbolic orbit a
 * decade from perihelion, where the first few steps are bisections of a very
 * wide bracket.
 */
internal fun solveUniversalAnomaly(q: Double, e: Double, dtDays: Double): UniversalAnomaly? {
    // alpha = 1/a: positive for ellipses, 0 for parabolas, negative for hyperbolas — no branch.
    val alpha = (1.0 - e) / q

    fun f(x: Double): Pair<Double, Double> {
        val z = alpha * x * x
        val (c2, c3) = stumpff(z)
        val oneMinusAlphaQ = 1.0 - alpha * q
        val value = oneMinusAlphaQ * x * x * x * c3 + q * x - GAUSS_K * dtDays
        val derivative = oneMinusAlphaQ * x * x * c2 + q
        return value to derivative
    }

    // x = 0 is the root exactly at perihelion passage; f(0) = 0 there.
    if (dtDays == 0.0) return UniversalAnomaly(0.0, 0)

    val bound = GAUSS_K * dtDays / q
    var low = minOf(0.0, bound)
    var high = maxOf(0.0, bound)

    // Start from the bound rather than the midpoint: for the near-parabolic
    // and hyperbolic orbits where q is close to r it is already very nearly
    // the root, and the safeguard below costs one bisection when it isn't.
    var x = bound
    var (value, derivative) = f(x)
    var step = high - low
    var previousStep = step

    for (iteration in 0 until KEPLER_MAX_ITERATIONS) {
        if (abs(value) < KEPLER_TOLERANCE) return UniversalAnomaly(x, iteration)

        val newtonStep = value / derivative
        val newtonWouldLeaveBracket = ((x - high) * derivative - value) * ((x - low) * derivative - value) > 0.0
        val newtonIsTooSlow = abs(2.0 * value) > abs(previousStep * derivative)
        // Far out on a hyperbola the Stumpff functions overflow: c2 and c3
        // are built from cosh/sinh of sqrt(-alpha)*|x|, which for an orbit
        // like e=3, q=0.12 au ten years from perihelion is sinh(2000). Both
        // f and f' become infinite — with the right *signs*, so the bracket
        // is still sound and bisection still works, but their ratio is NaN
        // and a Newton step from it would poison x for good.
        previousStep = step
        if (!newtonStep.isFinite() || newtonWouldLeaveBracket || newtonIsTooSlow) {
            step = 0.5 * (high - low)
            x = low + step
        } else {
            step = newtonStep
            x -= step
        }
        // A vanishing step is an equally valid convergence signal as a small
        // residual: f(x) sums terms of very different magnitude (the Stumpff
        // cubic term vs. GAUSS_K*dt), so for some regimes (near-parabolic
        // orbits at large |dt|, i.e. alpha ~ 0 with dt in the thousands of
        // days — see KeplerTest.convergesForNearParabolicOrbitsAcrossLongTimeSpans)
        // floating-point cancellation keeps |f(x)| a couple of ULPs above
        // KEPLER_TOLERANCE forever even once x itself has stopped moving.
        if (abs(step) < 1e-12 * maxOf(1.0, abs(x))) return UniversalAnomaly(x, iteration + 1)

        val next = f(x)
        value = next.first
        derivative = next.second
        // f is strictly increasing, so the sign of f(x) says which side of
        // the root x fell on and the bracket only ever narrows.
        if (value < 0.0) low = x else high = x
    }
    return null
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
    val alpha = (1.0 - e) / q

    val x = solveUniversalAnomaly(q, e, dt)?.x ?: return null

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
