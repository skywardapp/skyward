package dev.fritze.skyward.core.visibility

import dev.fritze.skyward.core.astro.altitudeDeg
import dev.fritze.skyward.core.astro.darknessWindow
import dev.fritze.skyward.core.astro.timeSamples
import dev.fritze.skyward.core.astro.toAstroTime
import dev.fritze.skyward.core.astro.toInstant
import dev.fritze.skyward.core.model.LocalDetails
import dev.fritze.skyward.core.model.MeteorShowerPayload
import dev.fritze.skyward.core.model.Occurrence
import dev.fritze.skyward.core.model.Phenomenon
import dev.fritze.skyward.core.model.Quality
import dev.fritze.skyward.core.model.SavedLocation
import dev.fritze.skyward.core.model.VisibilityResult
import io.github.cosinekitty.astronomy.Body
import io.github.cosinekitty.astronomy.Observer
import io.github.cosinekitty.astronomy.Refraction
import io.github.cosinekitty.astronomy.Time
import io.github.cosinekitty.astronomy.horizon
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/**
 * §8.5: no distance concept — the sky is the venue, so travel fields are
 * always null. Visibility hinges entirely on whether the radiant clears
 * 20 deg altitude at some point during the astronomical night (sun < -12
 * deg) containing the shower's peak, sampled hourly per the design doc.
 */
class MeteorShowerVisibilityModel : VisibilityModel {
    override val phenomenon = Phenomenon.METEOR_SHOWER

    override fun evaluate(occ: Occurrence, loc: SavedLocation, ctx: VisibilityContext): VisibilityResult {
        val payload = occ.payload as MeteorShowerPayload
        val observer = Observer(loc.point.latDeg, loc.point.lonDeg, 0.0)
        val peakTime = requireNotNull(occ.peakTime) { "meteor shower occurrences always have a peakTime" }

        val night = darknessWindow(peakTime.toAstroTime(), observer)
        if (night == null) {
            return VisibilityResult(
                visibleAtLocation = false,
                quality = Quality.NONE,
                localDetails = LocalDetails.MeteorLocal(
                    bestViewingStart = null,
                    bestViewingEnd = null,
                    maxRadiantAltDeg = radiantAltitudeDeg(payload, peakTime.toAstroTime(), observer),
                    moonIllumination = payload.moonIlluminationAtPeak,
                    moonUpDuringBest = false,
                ),
                nearestVisiblePoint = null,
                travelDistanceKm = null,
                travelBearingDeg = null,
                qualityAtNearestPoint = null,
            )
        }

        val hourTimes = timeSamples(night.start, night.end, 1.hours)
        val radiantAlts = hourTimes.map { radiantAltitudeDeg(payload, it, observer) }
        val moonAlts = hourTimes.map { altitudeDeg(Body.Moon, it, observer) }
        val maxRadiantAlt = radiantAlts.max()

        val aboveTwentyIndices = radiantAlts.indices.filter { radiantAlts[it] >= 20.0 }
        val bestViewingStart = aboveTwentyIndices.minOrNull()?.let { hourTimes[it].toInstant() }
        val bestViewingEnd = aboveTwentyIndices.maxOrNull()?.let { hourTimes[it].toInstant() }
        val moonUpDuringBest = aboveTwentyIndices.any { moonAlts[it] > 0.0 }

        // §8.5 quality ladder, expressed as levels matching Quality's ordinals
        // (NONE=0, MARGINAL=1, GOOD=2, EXCELLENT=3) so the two penalties and
        // the MARGINAL floor compose as simple integer arithmetic.
        var level = when {
            (payload.zhr ?: 0) >= 60 -> 3
            (payload.zhr ?: 0) >= 20 -> 2
            else -> 1 // ZHR 10-19, <10, or null/variable — all MARGINAL base (§8.5)
        }
        if (payload.moonIlluminationAtPeak > 0.6 && moonUpDuringBest) level -= 1
        if (maxRadiantAlt < 35.0) level -= 1
        level = if (maxRadiantAlt >= 20.0) level.coerceAtLeast(1) else 0

        val quality = Quality.entries[level]
        val localDetails = LocalDetails.MeteorLocal(
            bestViewingStart = bestViewingStart,
            bestViewingEnd = bestViewingEnd,
            maxRadiantAltDeg = maxRadiantAlt,
            moonIllumination = payload.moonIlluminationAtPeak,
            moonUpDuringBest = moonUpDuringBest,
        )

        return VisibilityResult(
            visibleAtLocation = quality != Quality.NONE,
            quality = quality,
            localDetails = localDetails,
            nearestVisiblePoint = null,
            travelDistanceKm = null,
            travelBearingDeg = null,
            qualityAtNearestPoint = null,
        )
    }

    /**
     * The catalog's radiant RA/Dec are J2000 (per OccurrencePayload's doc
     * comment); `horizon()` technically wants equator-of-date coordinates,
     * but precession is ~0.014 deg/year — negligible next to the 20/35 deg
     * thresholds this model checks over the app's few-year horizon, so no
     * precession step is applied here.
     */
    private fun radiantAltitudeDeg(payload: MeteorShowerPayload, time: Time, observer: Observer): Double =
        horizon(time, observer, payload.radiantRaDeg / 15.0, payload.radiantDecDeg, Refraction.Normal).altitude
}
