package dev.fritze.skyward.core.visibility

import dev.fritze.skyward.core.astro.darknessWindow
import dev.fritze.skyward.core.astro.toAstroTime
import dev.fritze.skyward.core.astro.toInstant
import dev.fritze.skyward.core.model.AuroraForecastKind
import dev.fritze.skyward.core.model.AuroraPayload
import dev.fritze.skyward.core.model.GeoPoint
import dev.fritze.skyward.core.model.LocalDetails
import dev.fritze.skyward.core.model.Occurrence
import dev.fritze.skyward.core.model.Phenomenon
import dev.fritze.skyward.core.model.Quality
import dev.fritze.skyward.core.model.SavedLocation
import dev.fritze.skyward.core.model.VisibilityResult
import io.github.cosinekitty.astronomy.Observer
import kotlin.math.abs
import kotlin.math.cos

/**
 * §8.4. Two regimes sharing a common geomagnetic-latitude base and darkness
 * gate: THREE_DAY (Kp-threshold planning) and NOWCAST (OVATION grid probability).
 */
class AuroraVisibilityModel : VisibilityModel {
    override val phenomenon = Phenomenon.AURORA

    override fun evaluate(occ: Occurrence, loc: SavedLocation, ctx: VisibilityContext): VisibilityResult {
        val payload = occ.payload as AuroraPayload
        val gmLat = geomagneticLatitudeDeg(loc.point)
        val absGmLat = abs(gmLat)
        // Minimum Kp at which this location's visibility boundary (66 - 2*Kp)
        // reaches its own geomagnetic latitude; clamped since a very high
        // geomagnetic latitude can already be inside the boundary at Kp=0.
        val kpNeeded = ((66.0 - absGmLat) / 2.0).coerceAtLeast(0.0)

        val (baseQuality, travelTarget, travelTargetQuality) = when (payload.forecastKind) {
            AuroraForecastKind.THREE_DAY -> threeDayQuality(payload, loc.point, absGmLat)
            AuroraForecastKind.NOWCAST -> nowcastQuality(loc.point, ctx)
        }

        val observer = Observer(loc.point.latDeg, loc.point.lonDeg, 0.0)
        val night = darknessWindow(occ.window.start.toAstroTime(), observer)
        val overlapsNight = night != null &&
            night.start.tt < occ.window.end.toAstroTime().tt &&
            night.end.tt > occ.window.start.toAstroTime().tt
        // §8.4: aurora needs darkness — if the slot doesn't overlap local
        // astronomical night, cap quality at MARGINAL (never raises it).
        val quality = if (overlapsNight) baseQuality else minOf(baseQuality, Quality.MARGINAL)
        val visibleAtLocation = quality != Quality.NONE

        val ovationProbability = if (payload.forecastKind == AuroraForecastKind.NOWCAST) {
            ctx.ovationGrid?.probabilityAt(loc.point)?.toInt()
        } else {
            null
        }
        val localDetails = LocalDetails.AuroraLocal(
            geomagneticLatDeg = gmLat,
            kpNeeded = kpNeeded,
            ovationProbability = ovationProbability,
            darknessStart = night?.start?.toInstant(),
            darknessEnd = night?.end?.toInstant(),
        )

        if (visibleAtLocation || travelTarget == null) {
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

        return VisibilityResult(
            visibleAtLocation = false,
            quality = quality,
            localDetails = localDetails,
            nearestVisiblePoint = travelTarget,
            travelDistanceKm = haversineDistanceKm(loc.point, travelTarget),
            travelBearingDeg = initialBearingDeg(loc.point, travelTarget),
            qualityAtNearestPoint = travelTargetQuality,
        )
    }

    /** A regime's own-location quality plus, if not visible, where to travel and what quality that target reaches. */
    private data class RegimeResult(val quality: Quality, val travelTarget: GeoPoint?, val travelTargetQuality: Quality?)

    /** THREE_DAY: Kp-threshold visibility boundary. Its travel target sits exactly at the MARGINAL boundary. */
    private fun threeDayQuality(payload: AuroraPayload, loc: GeoPoint, absGmLat: Double): RegimeResult {
        val visLat = 66.0 - 2.0 * payload.kpForecast
        val quality = when {
            absGmLat >= visLat + 4.0 -> Quality.EXCELLENT
            absGmLat >= visLat + 1.5 -> Quality.GOOD
            absGmLat >= visLat - 1.0 -> Quality.MARGINAL
            else -> Quality.NONE
        }
        if (quality != Quality.NONE) return RegimeResult(quality, null, null)

        // §8.4: move (visLat - absGmLat) deg along the great circle toward
        // the geomagnetic pole; ~111.2 km/deg (matches EARTH_RADIUS_KM*PI/180).
        val deltaGmLatDeg = visLat - absGmLat
        val bearingToPole = initialBearingDeg(loc, GEOMAGNETIC_POLE)
        val target = destinationPoint(loc, deltaGmLatDeg * KM_PER_DEGREE, bearingToPole)
        return RegimeResult(quality, target, Quality.MARGINAL)
    }

    /** NOWCAST: OVATION grid probability. Its travel target is the nearest probability>=50 cell, i.e. GOOD. */
    private fun nowcastQuality(loc: GeoPoint, ctx: VisibilityContext): RegimeResult {
        val grid = ctx.ovationGrid ?: return RegimeResult(Quality.NONE, null, null)
        val probability = grid.probabilityAt(loc)
        val quality = when {
            probability >= 70.0 -> Quality.EXCELLENT
            probability >= 50.0 -> Quality.GOOD
            probability >= 25.0 -> Quality.MARGINAL
            else -> Quality.NONE
        }
        if (quality != Quality.NONE) return RegimeResult(quality, null, null)

        var nearest: GeoPoint? = null
        var nearestDistanceKm = Double.MAX_VALUE
        val latSpan = (NOWCAST_TRAVEL_RADIUS_KM / KM_PER_DEGREE).toInt() + 1
        for (dLat in -latSpan..latSpan) {
            val candidateLat = loc.latDeg + dLat
            if (candidateLat < -90.0 || candidateLat > 90.0) continue
            // Longitude degree-span needed to cover the radius shrinks with
            // cos(lat); over-scan a little and let the haversine filter below
            // reject anything actually outside NOWCAST_TRAVEL_RADIUS_KM.
            val cosLat = cos(candidateLat.toRadians()).coerceAtLeast(0.05)
            val lonSpan = (NOWCAST_TRAVEL_RADIUS_KM / (KM_PER_DEGREE * cosLat)).toInt() + 1
            for (dLon in -lonSpan..lonSpan) {
                val normalizedLon = ((loc.lonDeg + dLon + 540.0) % 360.0) - 180.0
                val candidate = GeoPoint(candidateLat, normalizedLon)
                val distanceKm = haversineDistanceKm(loc, candidate)
                if (distanceKm > NOWCAST_TRAVEL_RADIUS_KM) continue
                if (grid.probabilityAt(candidate) < 50.0) continue
                if (distanceKm < nearestDistanceKm) {
                    nearestDistanceKm = distanceKm
                    nearest = candidate
                }
            }
        }
        return RegimeResult(quality, nearest, nearest?.let { Quality.GOOD })
    }

    private companion object {
        const val KM_PER_DEGREE = 111.2
        const val NOWCAST_TRAVEL_RADIUS_KM = 800.0
    }
}
