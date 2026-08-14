package dev.fritze.skyward.core.visibility

import dev.fritze.skyward.core.model.LocalDetails
import dev.fritze.skyward.core.model.Occurrence
import dev.fritze.skyward.core.model.Phenomenon
import dev.fritze.skyward.core.model.Quality
import dev.fritze.skyward.core.model.SavedLocation
import dev.fritze.skyward.core.model.TerrestrialPayload
import dev.fritze.skyward.core.model.VisibilityResult

/**
 * §8.8 (EONET): the model never sees rule parameters, so it always reports
 * `visibleAtLocation = false` and lets the rule engine's `ReachableWithin`
 * threshold the raw distance. Quality is fixed GOOD — EONET has no
 * meaningful quality rubric of its own.
 */
class TerrestrialVisibilityModel : VisibilityModel {
    override val phenomenon = Phenomenon.TERRESTRIAL

    override fun evaluate(occ: Occurrence, loc: SavedLocation, ctx: VisibilityContext): VisibilityResult {
        val payload = occ.payload as TerrestrialPayload
        return VisibilityResult(
            visibleAtLocation = false,
            quality = Quality.GOOD,
            localDetails = LocalDetails.GenericLocal(payload.categoryTitle),
            nearestVisiblePoint = payload.latestGeometry,
            travelDistanceKm = haversineDistanceKm(loc.point, payload.latestGeometry),
            travelBearingDeg = initialBearingDeg(loc.point, payload.latestGeometry),
            qualityAtNearestPoint = Quality.GOOD,
        )
    }
}
