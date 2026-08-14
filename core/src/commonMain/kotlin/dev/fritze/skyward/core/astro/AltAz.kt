package dev.fritze.skyward.core.astro

import dev.fritze.skyward.core.model.GeoPoint
import io.github.cosinekitty.astronomy.Aberration
import io.github.cosinekitty.astronomy.Body
import io.github.cosinekitty.astronomy.Direction
import io.github.cosinekitty.astronomy.EquatorEpoch
import io.github.cosinekitty.astronomy.Observer
import io.github.cosinekitty.astronomy.Refraction
import io.github.cosinekitty.astronomy.Time
import io.github.cosinekitty.astronomy.equator
import io.github.cosinekitty.astronomy.horizon
import io.github.cosinekitty.astronomy.searchAltitude
import io.github.cosinekitty.astronomy.searchRiseSet
import io.github.cosinekitty.astronomy.siderealTime
import kotlin.time.Duration

/**
 * `[start, end]` sampled every [step], always including `end` itself even
 * when it doesn't fall on an exact step boundary. Shared by the visibility
 * models that scan a window at a fixed cadence looking for the best/any
 * qualifying instant (meteor showers, comets, conjunctions).
 */
fun timeSamples(start: Time, end: Time, step: Duration): List<Time> {
    val stepDays = step.inWholeSeconds / 86_400.0
    val times = mutableListOf<Time>()
    var t = start
    while (t.tt <= end.tt) {
        times += t
        t = t.addDays(stepDays)
    }
    if (times.last().tt < end.tt) times += end
    return times
}

/** Apparent altitude of [body] above the horizon at [observer], in degrees, refraction-corrected. */
fun altitudeDeg(body: Body, time: Time, observer: Observer): Double {
    val equ = equator(body, time, observer, EquatorEpoch.OfDate, Aberration.Corrected)
    return horizon(time, observer, equ.ra, equ.dec, Refraction.Normal).altitude
}

/**
 * The geographic point directly beneath [body] at [time] (where it sits at
 * the zenith) — latitude = apparent declination, longitude derived from
 * Greenwich Apparent Sidereal Time. Uses an observer at the geocenter's
 * surface projection (lat=lon=height=0) rather than a true geocentric
 * vector, which introduces a parallax error of at most a degree or so for
 * the Moon (negligible for the trip-planning-only travel guidance this
 * feeds, §8.1/§8.3 — "often thousands of km" is the expected scale).
 */
fun subPoint(body: Body, time: Time): GeoPoint {
    val equ = equator(body, time, Observer(0.0, 0.0, 0.0), EquatorEpoch.OfDate, Aberration.Corrected)
    val lonDeg = ((equ.ra * 15.0 - siderealTime(time) * 15.0 + 540.0) % 360.0) - 180.0
    return GeoPoint(equ.dec, lonDeg)
}

/**
 * The sub-interval of `[windowStart, windowEnd]` during which [body] is
 * above the horizon at [observer], assuming at most one rise and/or one set
 * occurs within the window (true for any window on the order of hours, as
 * used for eclipse phase durations). [fraction] is that sub-interval's share
 * of the total window length; when [fraction] is 0.0, [start]/[end] both
 * equal [windowStart] (no meaningful visible sub-window to report).
 */
data class VisibleWindow(val start: Time, val end: Time, val fraction: Double)

fun visibleWindow(body: Body, windowStart: Time, windowEnd: Time, observer: Observer): VisibleWindow {
    val totalDays = windowEnd.tt - windowStart.tt
    if (totalDays <= 0.0) {
        return if (altitudeDeg(body, windowStart, observer) > 0.0) {
            VisibleWindow(windowStart, windowStart, 1.0)
        } else {
            VisibleWindow(windowStart, windowStart, 0.0)
        }
    }

    val upAtStart = altitudeDeg(body, windowStart, observer) > 0.0
    val upAtEnd = altitudeDeg(body, windowEnd, observer) > 0.0
    return when {
        upAtStart && upAtEnd -> VisibleWindow(windowStart, windowEnd, 1.0)
        !upAtStart && !upAtEnd -> VisibleWindow(windowStart, windowStart, 0.0)
        upAtStart -> { // sets partway through
            val setTime = searchRiseSet(body, observer, Direction.Set, windowStart, totalDays) ?: windowEnd
            VisibleWindow(windowStart, setTime, ((setTime.tt - windowStart.tt) / totalDays).coerceIn(0.0, 1.0))
        }
        else -> { // rises partway through
            val riseTime = searchRiseSet(body, observer, Direction.Rise, windowStart, totalDays) ?: windowEnd
            VisibleWindow(riseTime, windowEnd, ((windowEnd.tt - riseTime.tt) / totalDays).coerceIn(0.0, 1.0))
        }
    }
}

/** The fraction-only view of [visibleWindow] — see its doc for semantics. */
fun visibleFraction(body: Body, windowStart: Time, windowEnd: Time, observer: Observer): Double =
    visibleWindow(body, windowStart, windowEnd, observer).fraction

/** Astronomical night boundaries: sun altitude <= [altitudeDeg] (default -12, per §8.4/§8.5/§8.6). */
data class DarknessWindow(val start: Time, val end: Time)

/**
 * The astronomical-night window (sun descends through [altitudeDeg] to sun
 * ascends back through it) that contains [time] if [time] itself is already
 * dark, otherwise the *next* such window after [time] — i.e. "tonight" for a
 * daytime instant, matching how the design doc's models use "the night
 * containing peakTime/now" for an instant that may fall during daylight.
 *
 * Returns `null` if no such window exists within [searchLimitDays] in either
 * direction (permanent daylight at high summer latitudes — §8.4's "no
 * astronomical night (midsummer)" case).
 */
fun darknessWindow(time: Time, observer: Observer, altitudeDeg: Double = -12.0, searchLimitDays: Double = 2.0): DarknessWindow? {
    val duskBefore = searchAltitude(Body.Sun, observer, Direction.Set, time, -searchLimitDays, altitudeDeg)
    if (duskBefore != null) {
        val dawnAfterThatDusk = searchAltitude(Body.Sun, observer, Direction.Rise, duskBefore, searchLimitDays, altitudeDeg)
        if (dawnAfterThatDusk != null && time.tt in duskBefore.tt..dawnAfterThatDusk.tt) {
            return DarknessWindow(duskBefore, dawnAfterThatDusk)
        }
    }
    val duskAfter = searchAltitude(Body.Sun, observer, Direction.Set, time, searchLimitDays, altitudeDeg) ?: return null
    val dawnAfter = searchAltitude(Body.Sun, observer, Direction.Rise, duskAfter, searchLimitDays, altitudeDeg) ?: return null
    return DarknessWindow(duskAfter, dawnAfter)
}
