package dev.fritze.skyward.core.visibility

import dev.fritze.skyward.core.astro.darknessWindow
import dev.fritze.skyward.core.astro.toAstroTime
import dev.fritze.skyward.core.astro.visibleFraction
import dev.fritze.skyward.core.model.LocalDetails
import dev.fritze.skyward.core.model.MoonEventPayload
import dev.fritze.skyward.core.model.Occurrence
import dev.fritze.skyward.core.model.Phenomenon
import dev.fritze.skyward.core.model.Quality
import dev.fritze.skyward.core.model.SavedLocation
import dev.fritze.skyward.core.model.VisibilityResult
import io.github.cosinekitty.astronomy.Body
import io.github.cosinekitty.astronomy.Observer

/**
 * §8.7 (supermoon half): visible if the Moon is up at any point during the
 * astronomical night of the full moon (nearly always true). No travel
 * concept — the sky is the venue, same as meteor showers.
 */
class MoonEventVisibilityModel : VisibilityModel {
    override val phenomenon = Phenomenon.MOON_EVENT

    override fun evaluate(occ: Occurrence, loc: SavedLocation, ctx: VisibilityContext): VisibilityResult {
        val payload = occ.payload as MoonEventPayload
        val observer = Observer(loc.point.latDeg, loc.point.lonDeg, 0.0)

        val night = darknessWindow(payload.fullMoonTime.toAstroTime(), observer)
        val moonUp = night != null && visibleFraction(Body.Moon, night.start, night.end, observer) > 0.0

        val quality = when {
            !moonUp -> Quality.NONE
            payload.perigeeDistanceKm < SUPERMOON_EXCELLENT_DISTANCE_KM -> Quality.EXCELLENT
            else -> Quality.GOOD
        }

        return VisibilityResult(
            visibleAtLocation = quality != Quality.NONE,
            quality = quality,
            localDetails = LocalDetails.GenericLocal(
                "Perigee ${payload.perigeeDistanceKm.toInt()} km at full moon",
            ),
            nearestVisiblePoint = null,
            travelDistanceKm = null,
            travelBearingDeg = null,
            qualityAtNearestPoint = null,
        )
    }

    private companion object {
        const val SUPERMOON_EXCELLENT_DISTANCE_KM = 357_000.0
    }
}
