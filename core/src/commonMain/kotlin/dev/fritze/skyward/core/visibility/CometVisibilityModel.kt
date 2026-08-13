package dev.fritze.skyward.core.visibility

import dev.fritze.skyward.core.astro.Vec3
import dev.fritze.skyward.core.astro.altitudeDeg
import dev.fritze.skyward.core.astro.apparentMagnitude
import dev.fritze.skyward.core.astro.darknessWindow
import dev.fritze.skyward.core.astro.earthHeliocentricPositionEcliptic
import dev.fritze.skyward.core.astro.heliocentricPosition
import dev.fritze.skyward.core.astro.toAstroTime
import dev.fritze.skyward.core.astro.toInstant
import dev.fritze.skyward.core.model.CometPayload
import dev.fritze.skyward.core.model.LocalDetails
import dev.fritze.skyward.core.model.Occurrence
import dev.fritze.skyward.core.model.Phenomenon
import dev.fritze.skyward.core.model.Quality
import dev.fritze.skyward.core.model.SavedLocation
import dev.fritze.skyward.core.model.VisibilityResult
import io.github.cosinekitty.astronomy.Body
import io.github.cosinekitty.astronomy.Observer
import io.github.cosinekitty.astronomy.Refraction
import io.github.cosinekitty.astronomy.Time
import io.github.cosinekitty.astronomy.Vector
import io.github.cosinekitty.astronomy.horizon
import io.github.cosinekitty.astronomy.illumination
import io.github.cosinekitty.astronomy.rotationEclEqj
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/**
 * §8.6: a real visibility model backed by the Kepler propagator (§7.4.2),
 * not just a magnitude filter. Evaluated over the night containing `now`,
 * or containing `occ.peakTime` when the peak is still in the future (i.e.
 * "how will this look at its best" before peak, "how does this look
 * tonight" after). Travel fields are always null — this is a timing/
 * latitude matter, not a distance one.
 */
class CometVisibilityModel : VisibilityModel {
    override val phenomenon = Phenomenon.COMET

    override fun evaluate(occ: Occurrence, loc: SavedLocation, ctx: VisibilityContext): VisibilityResult {
        val payload = occ.payload as CometPayload
        val observer = Observer(loc.point.latDeg, loc.point.lonDeg, 0.0)
        val peakTime = occ.peakTime
        val referenceTime = if (peakTime != null && peakTime > ctx.now) peakTime else ctx.now

        val night = darknessWindow(referenceTime.toAstroTime(), observer)
        val mag = apparentMagnitude(payload.elements, payload.magParams, referenceTime) ?: payload.peakMag

        if (night == null) {
            return VisibilityResult(
                visibleAtLocation = false,
                quality = Quality.NONE,
                localDetails = LocalDetails.CometLocal(
                    predictedMag = mag,
                    elementEpoch = payload.elements.epoch,
                    maxAltDeg = altitudeAt(payload, referenceTime.toAstroTime(), observer) ?: 0.0,
                    maxAltTime = null,
                    bestViewingStart = null,
                    bestViewingEnd = null,
                ),
                nearestVisiblePoint = null,
                travelDistanceKm = null,
                travelBearingDeg = null,
                qualityAtNearestPoint = null,
            )
        }

        val hourTimes = hourlySamples(night.start, night.end)
        val altitudes = hourTimes.map { altitudeAt(payload, it, observer) ?: -90.0 }
        val maxAltIdx = altitudes.indices.maxBy { altitudes[it] }
        val maxAlt = altitudes[maxAltIdx]

        val magnitudeGatePassed = mag <= MAGNITUDE_GATE
        val altitudeGatePassed = maxAlt >= ALTITUDE_GATE_DEG

        val cometUpIndices = altitudes.indices.filter { altitudes[it] > 0.0 }
        val bestViewingStart = cometUpIndices.minOrNull()?.let { hourTimes[it].toInstant() }
        val bestViewingEnd = cometUpIndices.maxOrNull()?.let { hourTimes[it].toInstant() }

        val quality: Quality
        if (!magnitudeGatePassed || !altitudeGatePassed) {
            quality = Quality.NONE
        } else {
            var level = when {
                mag <= 2.0 -> 3 // EXCELLENT
                mag <= 4.0 -> 2 // GOOD
                else -> 1 // MARGINAL (mag <= 6.0, guaranteed by the gate)
            }
            if (maxAlt < BRIGHT_ALTITUDE_DEG) level -= 1

            val moonIllumination = illumination(Body.Moon, referenceTime.toAstroTime()).phaseFraction
            val moonUpDuringCometWindow = cometUpIndices.any { altitudeDeg(Body.Moon, hourTimes[it], observer) > 0.0 }
            if (moonUpDuringCometWindow && moonIllumination > 0.6) level -= 1

            quality = Quality.entries[level.coerceAtLeast(1)] // floor MARGINAL while both gates pass
        }

        return VisibilityResult(
            visibleAtLocation = quality != Quality.NONE,
            quality = quality,
            localDetails = LocalDetails.CometLocal(
                predictedMag = mag,
                elementEpoch = payload.elements.epoch,
                maxAltDeg = maxAlt,
                maxAltTime = hourTimes[maxAltIdx].toInstant(),
                bestViewingStart = bestViewingStart,
                bestViewingEnd = bestViewingEnd,
            ),
            nearestVisiblePoint = null,
            travelDistanceKm = null,
            travelBearingDeg = null,
            qualityAtNearestPoint = null,
        )
    }

    private fun altitudeAt(payload: CometPayload, time: Time, observer: Observer): Double? {
        val instant = time.toInstant()
        val cometHelio = heliocentricPosition(payload.elements, instant) ?: return null
        val earthHelio = earthHeliocentricPositionEcliptic(instant)
        val geocentricEcliptic = cometHelio - earthHelio
        val equ = eclipticVecToEquatorial(geocentricEcliptic, time)
        return horizon(time, observer, equ.ra, equ.dec, Refraction.Normal).altitude
    }

    /**
     * `heliocentricPosition`/`earthHeliocentricPositionEcliptic` work in the
     * J2000 ecliptic frame (Kepler.kt); rotate to J2000 equatorial (EQJ) for
     * `horizon()`. Same J2000-not-of-date approximation as the meteor
     * shower model — precession is negligible against this model's 15/25
     * deg thresholds over the app's few-year horizon.
     */
    private fun eclipticVecToEquatorial(v: Vec3, time: Time) =
        rotationEclEqj().rotate(Vector(v.x, v.y, v.z, time)).toEquatorial()

    private fun hourlySamples(start: Time, end: Time): List<Time> {
        val times = mutableListOf<Time>()
        var t = start
        while (t.tt <= end.tt) {
            times += t
            t = t.addDays(1.hours.inWholeSeconds / 86_400.0)
        }
        if (times.last().tt < end.tt) times += end
        return times
    }

    private companion object {
        const val MAGNITUDE_GATE = 6.0
        const val ALTITUDE_GATE_DEG = 15.0
        const val BRIGHT_ALTITUDE_DEG = 25.0
    }
}
