package dev.fritze.skyward.core.astro

import io.github.cosinekitty.astronomy.sunPosition
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/**
 * §7.2: the meteor shower catalog's `start`/`finish`/`peak` fields are the
 * Sun's apparent geocentric ecliptic longitude of date, in degrees (λ☉,
 * 0..360) — not calendar dates. Rather than port Stellarium's own
 * curve-fit approximation (`JDfromSolarLongitude` in `MeteorShower.cpp`),
 * this finds the instant directly from the vendored Astronomy Engine's own
 * [sunPosition], which is more precise and keeps every date computed in
 * this codebase on the same footing.
 *
 * Solar longitude increases monotonically and near-uniformly (~0.9856
 * deg/day) with no local extrema across a year, so a handful of
 * fixed-rate correction steps converges comfortably past the precision
 * this app needs (shower peaks are inherently broad — see §7.2.2 step 3).
 */
private const val MEAN_SOLAR_LONGITUDE_RATE_DEG_PER_DAY = 360.0 / 365.2422 // tropical year

/**
 * The first instant on/after Jan 1 of [year] (UTC) at which the Sun's
 * ecliptic longitude equals [targetDeg]. Intended for finding a shower's
 * *peak* within a specific calendar year — use [instantForSolarLongitudeNear]
 * for a window's start/finish, anchored to that peak, to sidestep the
 * year-boundary ambiguity a shower like the Quadrantids has (see its note).
 */
fun instantForSolarLongitudeInYear(targetDeg: Double, year: Int): Instant {
    val jan1 = LocalDateTime(year, 1, 1, 0, 0, 0).toInstant(TimeZone.UTC)
    // lambda-sun(Jan 1) is ~280.8 deg (lambda=0 is the March equinox, not New
    // Year's Day) — seeding from an assumed lambda(Jan1)=0 would put showers
    // with target < ~280 a full year off. Anchor the seed to the Sun's
    // actual longitude at Jan 1 of the requested year instead.
    val lonAtJan1 = sunPosition(jan1.toAstroTime()).elon
    val seed = jan1 + (normalizeDegrees(targetDeg - lonAtJan1) / MEAN_SOLAR_LONGITUDE_RATE_DEG_PER_DAY).days
    return refineSolarLongitude(targetDeg, seed)
}

/**
 * The instant closest to [anchor] (before or after) at which the Sun's
 * ecliptic longitude equals [targetDeg]. Used for a shower's start/finish,
 * anchored to its own peak instant — the window is narrow (days to a
 * couple of weeks) relative to a year, so "closest to the peak" is always
 * the intended crossing, including for a shower like the Quadrantids whose
 * catalog `start` (~Dec 22) numerically precedes its `peak` (~Jan 3) by
 * falling in the *previous* December — a plain "forward from Jan 1 of the
 * peak's year" search would instead wrap it to the following December.
 */
fun instantForSolarLongitudeNear(targetDeg: Double, anchor: Instant): Instant {
    val anchorLon = sunPosition(anchor.toAstroTime()).elon
    val seed = anchor + (normalizeSignedDegrees(targetDeg - anchorLon) / MEAN_SOLAR_LONGITUDE_RATE_DEG_PER_DAY).days
    return refineSolarLongitude(targetDeg, seed)
}

private fun refineSolarLongitude(targetDeg: Double, seed: Instant): Instant {
    var guess = seed
    repeat(8) {
        val currentLon = sunPosition(guess.toAstroTime()).elon
        val diff = normalizeSignedDegrees(targetDeg - currentLon)
        guess += (diff / MEAN_SOLAR_LONGITUDE_RATE_DEG_PER_DAY).days
    }
    return guess
}

private fun normalizeDegrees(deg: Double): Double {
    val m = deg % 360.0
    return if (m < 0) m + 360.0 else m
}

/** Normalizes to `(-180, 180]`, for shortest-path angular corrections. */
private fun normalizeSignedDegrees(deg: Double): Double {
    var d = deg % 360.0
    if (d <= -180.0) d += 360.0
    if (d > 180.0) d -= 360.0
    return d
}
