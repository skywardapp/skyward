package dev.fritze.skyward.core.visibility

import dev.fritze.skyward.core.astro.altitudeDeg
import dev.fritze.skyward.core.astro.subPoint
import dev.fritze.skyward.core.astro.toAstroTime
import dev.fritze.skyward.core.astro.toInstant
import dev.fritze.skyward.core.astro.visibleWindow
import dev.fritze.skyward.core.model.GeoPoint
import dev.fritze.skyward.core.model.LocalDetails
import dev.fritze.skyward.core.model.LunarEclipsePayload
import dev.fritze.skyward.core.model.Occurrence
import dev.fritze.skyward.core.model.Phenomenon
import dev.fritze.skyward.core.model.Quality
import dev.fritze.skyward.core.model.SavedLocation
import dev.fritze.skyward.core.model.VisibilityResult
import io.github.cosinekitty.astronomy.Body
import io.github.cosinekitty.astronomy.Observer
import io.github.cosinekitty.astronomy.Time

/**
 * §8.3: no path math — visible wherever the Moon is up during the
 * "interesting" phase (`totalBegin ?: partialBegin ?: penumbralBegin` and
 * its corresponding end). Quality grades how much of the *umbral* phase
 * (`partialBegin..partialEnd`, present for TOTAL/PARTIAL kinds) the Moon is
 * up for; a PENUMBRAL-only eclipse has no umbral phase and can only ever
 * reach MARGINAL.
 */
class LunarEclipseVisibilityModel : VisibilityModel {
    override val phenomenon = Phenomenon.LUNAR_ECLIPSE

    override fun evaluate(occ: Occurrence, loc: SavedLocation, ctx: VisibilityContext): VisibilityResult {
        val payload = occ.payload as LunarEclipsePayload
        val observer = Observer(loc.point.latDeg, loc.point.lonDeg, 0.0)

        val interestingStart = (payload.totalBegin ?: payload.partialBegin ?: payload.penumbralBegin).toAstroTime()
        val interestingEnd = (payload.totalEnd ?: payload.partialEnd ?: payload.penumbralEnd).toAstroTime()
        val interestingVisible = visibleWindow(Body.Moon, interestingStart, interestingEnd, observer)

        val umbralFraction = if (payload.partialBegin != null && payload.partialEnd != null) {
            visibleWindow(Body.Moon, payload.partialBegin.toAstroTime(), payload.partialEnd.toAstroTime(), observer).fraction
        } else {
            0.0
        }
        val hasUmbralPhase = payload.partialBegin != null && payload.partialEnd != null

        val quality = when {
            hasUmbralPhase && umbralFraction >= 1.0 -> Quality.EXCELLENT
            hasUmbralPhase && umbralFraction >= 0.5 -> Quality.GOOD
            hasUmbralPhase && umbralFraction > 0.0 -> Quality.MARGINAL
            !hasUmbralPhase && interestingVisible.fraction > 0.0 -> Quality.MARGINAL
            else -> Quality.NONE
        }
        val visibleAtLocation = quality != Quality.NONE

        val midTime = Time.fromTerrestrialTime((interestingStart.tt + interestingEnd.tt) / 2.0)
        val localDetails = LocalDetails.LunarEclipseLocal(
            visiblePhaseStart = interestingVisible.start.toInstant(),
            visiblePhaseEnd = interestingVisible.end.toInstant(),
            moonAltAtMidDeg = altitudeDeg(Body.Moon, midTime, observer),
            umbralFractionVisible = umbralFraction,
        )

        if (visibleAtLocation) {
            return VisibilityResult(
                visibleAtLocation = true,
                quality = quality,
                localDetails = localDetails,
                nearestVisiblePoint = null,
                travelDistanceKm = null,
                travelBearingDeg = null,
                qualityAtNearestPoint = null,
            )
        }

        val travelPoint = travelTarget(loc.point, midTime)
        return VisibilityResult(
            visibleAtLocation = false,
            quality = quality,
            localDetails = localDetails,
            nearestVisiblePoint = travelPoint,
            travelDistanceKm = travelPoint?.let { haversineDistanceKm(loc.point, it) },
            travelBearingDeg = travelPoint?.let { initialBearingDeg(loc.point, it) },
            qualityAtNearestPoint = travelPoint?.let { Quality.MARGINAL },
        )
    }

    /**
     * §8.3: walk the geodesic from [loc] toward the sub-lunar point at
     * [midTime] until Moon altitude exceeds 5deg, via bisection (up to 6
     * steps). Returns `null` if even the sub-lunar point itself doesn't
     * clear 5deg (shouldn't happen — altitude there is ~90deg by
     * definition — but the Moon could still be below the horizon *there*
     * only in the geometrically impossible case, so this is just defensive).
     */
    private fun travelTarget(loc: GeoPoint, midTime: Time): GeoPoint? {
        val subLunar = subPoint(Body.Moon, midTime)
        fun altitudeAt(p: GeoPoint) = altitudeDeg(Body.Moon, midTime, Observer(p.latDeg, p.lonDeg, 0.0))
        if (altitudeAt(subLunar) <= TRAVEL_TARGET_ALTITUDE_DEG) return null

        val distanceKm = haversineDistanceKm(loc, subLunar)
        val bearingDeg = initialBearingDeg(loc, subLunar)
        var lo = 0.0 // below threshold
        var hi = 1.0 // at or above threshold (the sub-lunar point itself)
        repeat(BISECTION_ITERATIONS) {
            val mid = (lo + hi) / 2.0
            if (altitudeAt(destinationPoint(loc, distanceKm * mid, bearingDeg)) > TRAVEL_TARGET_ALTITUDE_DEG) hi = mid else lo = mid
        }
        return destinationPoint(loc, distanceKm * hi, bearingDeg)
    }

    private companion object {
        const val TRAVEL_TARGET_ALTITUDE_DEG = 5.0
        const val BISECTION_ITERATIONS = 6
    }
}
