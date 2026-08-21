package dev.fritze.skyward.core.visibility

import dev.fritze.skyward.core.astro.toAstroTime
import dev.fritze.skyward.core.astro.toInstant
import dev.fritze.skyward.core.model.GeoPoint
import dev.fritze.skyward.core.model.LocalDetails
import dev.fritze.skyward.core.model.Occurrence
import dev.fritze.skyward.core.model.Phenomenon
import dev.fritze.skyward.core.model.PathSample
import dev.fritze.skyward.core.model.Quality
import dev.fritze.skyward.core.model.SavedLocation
import dev.fritze.skyward.core.model.SolarEclipseKind
import dev.fritze.skyward.core.model.SolarEclipsePayload
import dev.fritze.skyward.core.model.VisibilityResult
import io.github.cosinekitty.astronomy.EclipseKind
import io.github.cosinekitty.astronomy.LocalSolarEclipseInfo
import io.github.cosinekitty.astronomy.Observer
import io.github.cosinekitty.astronomy.Time
import io.github.cosinekitty.astronomy.searchLocalSolarEclipse
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/**
 * §8.2. Travel target for TOTAL/ANNULAR eclipses is the central path (people
 * travel for totality); for PARTIAL-only eclipses it's the nearest point
 * reaching 80% obscuration with the sun still above the horizon.
 */
class SolarEclipseVisibilityModel : VisibilityModel {
    override val phenomenon = Phenomenon.SOLAR_ECLIPSE

    override fun evaluate(occ: Occurrence, loc: SavedLocation, ctx: VisibilityContext): VisibilityResult {
        val payload = occ.payload as SolarEclipsePayload
        val observer = Observer(loc.point.latDeg, loc.point.lonDeg, 0.0)
        val searchStart = (occ.window.start - 1.hours).toAstroTime()
        val local = runCatching { searchLocalSolarEclipse(searchStart, observer) }.getOrNull()

        val quality: Quality
        val localDetails: LocalDetails.SolarEclipseLocal?
        if (local != null && local.peak.time.toInstant() in occ.window.start..occ.window.end) {
            quality = qualityOf(local)
            localDetails = LocalDetails.SolarEclipseLocal(
                partialBegin = local.partialBegin.time.toInstant(),
                peak = local.peak.time.toInstant(),
                partialEnd = local.partialEnd.time.toInstant(),
                maxObscuration = local.obscuration,
                sunAltAtPeakDeg = local.peak.altitude,
                localKind = mapKind(local.kind),
            )
        } else {
            quality = Quality.NONE
            localDetails = null
        }
        val visibleAtLocation = quality != Quality.NONE

        // Travel target's achievable quality: EXCELLENT (totality) when this
        // eclipse reaches totality anywhere, else GOOD (obscuration >= 0.8,
        // the partial-only travel target). "People travel for totality, not
        // for a bigger partial" (§8.2) — so travel guidance is offered any
        // time the location hasn't already reached *that* quality, even if
        // it already has a perfectly decent lesser partial view.
        val achievableQuality = if (payload.centralPath.isNotEmpty()) Quality.EXCELLENT else Quality.GOOD
        if (quality >= achievableQuality) {
            return VisibilityResult(
                visibleAtLocation = visibleAtLocation,
                quality = quality,
                localDetails = localDetails,
                nearestVisiblePoint = null,
                travelDistanceKm = null,
                travelBearingDeg = null,
                qualityAtNearestPoint = null,
            )
        }

        val travelTarget = if (payload.centralPath.isNotEmpty()) {
            refineNearestCentralPathPoint(loc.point, payload.centralPath) to Quality.EXCELLENT
        } else {
            nearestObscurationAtLeast(
                loc.point,
                payload.greatestEclipsePoint,
                PARTIAL_TRAVEL_TARGET_OBSCURATION,
                searchStart,
                occ.window.start..occ.window.end,
            )?.let { it to Quality.GOOD }
        }

        return VisibilityResult(
            visibleAtLocation = visibleAtLocation,
            quality = quality,
            localDetails = localDetails,
            nearestVisiblePoint = travelTarget?.first,
            travelDistanceKm = travelTarget?.first?.let { haversineDistanceKm(loc.point, it) },
            travelBearingDeg = travelTarget?.first?.let { initialBearingDeg(loc.point, it) },
            qualityAtNearestPoint = travelTarget?.second,
        )
    }

    private fun qualityOf(local: LocalSolarEclipseInfo): Quality = when {
        local.peak.altitude <= 0.0 -> Quality.NONE
        local.kind == EclipseKind.Total || local.kind == EclipseKind.Annular -> Quality.EXCELLENT
        local.obscuration >= 0.8 -> Quality.GOOD
        local.obscuration >= 0.2 -> Quality.MARGINAL
        else -> Quality.NONE
    }

    private fun mapKind(kind: EclipseKind): SolarEclipseKind = when (kind) {
        EclipseKind.Partial -> SolarEclipseKind.PARTIAL
        EclipseKind.Annular -> SolarEclipseKind.ANNULAR
        EclipseKind.Total -> SolarEclipseKind.TOTAL
        EclipseKind.Penumbral -> error("local solar eclipse search never returns Penumbral")
    }

    /**
     * Nearest point on the sampled central path to [loc]: nearest discrete
     * [PathSample] by haversine distance, refined by ternary search over the
     * two path segments adjacent to it (path samples are dense enough —
     * consecutive samples < 400 km apart, per EclipseSourceTest — that a
     * great-circle interpolation between them approximates the true path
     * well for this purpose).
     */
    private fun refineNearestCentralPathPoint(loc: GeoPoint, path: List<PathSample>): GeoPoint {
        val nearestIdx = path.indices.minBy { haversineDistanceKm(loc, path[it].point) }
        val lo = (nearestIdx - 1).coerceAtLeast(0)
        val hi = (nearestIdx + 1).coerceAtMost(path.size - 1)
        if (lo == hi) return path[nearestIdx].point

        fun pointAt(t: Double): GeoPoint {
            val i = t.toInt().coerceIn(lo, hi - 1)
            val frac = (t - i).coerceIn(0.0, 1.0)
            val a = path[i].point
            val b = path[i + 1].point
            val segmentKm = haversineDistanceKm(a, b)
            if (segmentKm < 1e-9) return a
            return destinationPoint(a, segmentKm * frac, initialBearingDeg(a, b))
        }

        var left = lo.toDouble()
        var right = hi.toDouble()
        repeat(TERNARY_SEARCH_ITERATIONS) {
            val m1 = left + (right - left) / 3.0
            val m2 = right - (right - left) / 3.0
            if (haversineDistanceKm(loc, pointAt(m1)) < haversineDistanceKm(loc, pointAt(m2))) {
                right = m2
            } else {
                left = m1
            }
        }
        return pointAt((left + right) / 2.0)
    }

    /**
     * Nearest point reaching [minObscuration] *with the sun above the
     * horizon at local peak*, searched along the geodesic from [loc] toward
     * [target] (the point of greatest eclipse) by bisection (§8.2: "8 steps
     * of bisection along the geodesic"). Returns `null` if no point along
     * that geodesic — [target] included — satisfies it.
     *
     * The altitude half of the predicate is what makes the travel target a
     * place the eclipse can actually be *seen*: §8.2 rates a deep partial
     * with the sun down as NONE, so a target chosen on obscuration alone
     * could sit past the terminator and promise a 90 % eclipse that happens
     * below the horizon.
     */
    private fun nearestObscurationAtLeast(
        loc: GeoPoint,
        target: GeoPoint,
        minObscuration: Double,
        searchStart: Time,
        window: ClosedRange<Instant>,
    ): GeoPoint? {
        fun isTravelWorthy(p: GeoPoint): Boolean {
            val local = runCatching { searchLocalSolarEclipse(searchStart, Observer(p.latDeg, p.lonDeg, 0.0)) }
                .getOrNull()
                // A probe far from this eclipse can find no local eclipse at
                // all *for this event* (peak below horizon there) and
                // `searchLocalSolarEclipse` then returns the *next* local
                // solar eclipse instead -- possibly months away. Reject
                // anything whose peak falls outside this occurrence's own
                // window so bisection never chases a different eclipse.
                ?.takeIf { it.peak.time.toInstant() in window } ?: return false
            return local.obscuration >= minObscuration && local.peak.altitude > 0.0
        }

        if (!isTravelWorthy(target)) return null
        // The bisection below only converges on the *nearest* qualifying
        // point while `loc` itself fails the predicate. The caller
        // guarantees that (it searches only when local quality is below
        // GOOD, and GOOD is exactly this predicate) -- but the guarantee
        // used to be obscuration-only, which a location just past the
        // terminator satisfied while rating NONE, collapsing the search
        // onto `loc` and advertising "travel 0 km" for an eclipse that
        // happens locally at night. Re-check rather than assume.
        if (isTravelWorthy(loc)) return null

        val distanceKm = haversineDistanceKm(loc, target)
        val bearingDeg = initialBearingDeg(loc, target)
        var lo = 0.0 // fails the predicate (loc itself, re-checked above)
        var hi = 1.0 // satisfies it (target)
        repeat(BISECTION_ITERATIONS) {
            val mid = (lo + hi) / 2.0
            if (isTravelWorthy(destinationPoint(loc, distanceKm * mid, bearingDeg))) {
                hi = mid
            } else {
                lo = mid
            }
        }
        return destinationPoint(loc, distanceKm * hi, bearingDeg)
    }

    private companion object {
        const val PARTIAL_TRAVEL_TARGET_OBSCURATION = 0.8
        const val TERNARY_SEARCH_ITERATIONS = 40
        const val BISECTION_ITERATIONS = 8
    }
}
