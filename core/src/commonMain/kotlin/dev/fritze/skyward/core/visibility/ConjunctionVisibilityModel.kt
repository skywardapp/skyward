package dev.fritze.skyward.core.visibility

import dev.fritze.skyward.core.astro.altitudeDeg
import dev.fritze.skyward.core.astro.toAstroTime
import dev.fritze.skyward.core.model.ConjunctionPayload
import dev.fritze.skyward.core.model.LocalDetails
import dev.fritze.skyward.core.model.Occurrence
import dev.fritze.skyward.core.model.Phenomenon
import dev.fritze.skyward.core.model.Quality
import dev.fritze.skyward.core.model.SavedLocation
import dev.fritze.skyward.core.model.VisibilityResult
import io.github.cosinekitty.astronomy.Body
import io.github.cosinekitty.astronomy.Observer
import io.github.cosinekitty.astronomy.Time
import kotlin.time.Duration.Companion.hours

/**
 * §8.7 (conjunction half): visible if both bodies are simultaneously above
 * 10 deg altitude with the Sun below -6 deg, at some point within +/-12h of
 * closest approach. No travel concept.
 */
class ConjunctionVisibilityModel : VisibilityModel {
    override val phenomenon = Phenomenon.CONJUNCTION

    override fun evaluate(occ: Occurrence, loc: SavedLocation, ctx: VisibilityContext): VisibilityResult {
        val payload = occ.payload as ConjunctionPayload
        val observer = Observer(loc.point.latDeg, loc.point.lonDeg, 0.0)
        val bodyA = Body.valueOf(payload.body1)
        val bodyB = Body.valueOf(payload.body2)

        val start = (payload.timeOfClosest - SEARCH_WINDOW).toAstroTime()
        val end = (payload.timeOfClosest + SEARCH_WINDOW).toAstroTime()
        val visibleAtSomePoint = hourlySamples(start, end).any { t ->
            altitudeDeg(bodyA, t, observer) > MIN_BODY_ALT_DEG &&
                altitudeDeg(bodyB, t, observer) > MIN_BODY_ALT_DEG &&
                altitudeDeg(Body.Sun, t, observer) < MAX_SUN_ALT_DEG
        }

        val quality = when {
            !visibleAtSomePoint -> Quality.NONE
            payload.minSeparationDeg < 0.5 -> Quality.EXCELLENT
            payload.minSeparationDeg < 1.0 -> Quality.GOOD
            else -> Quality.MARGINAL
        }

        return VisibilityResult(
            visibleAtLocation = quality != Quality.NONE,
            quality = quality,
            localDetails = LocalDetails.GenericLocal(
                "${payload.body1}-${payload.body2} separation ${payload.minSeparationDeg}deg",
            ),
            nearestVisiblePoint = null,
            travelDistanceKm = null,
            travelBearingDeg = null,
            qualityAtNearestPoint = null,
        )
    }

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
        val SEARCH_WINDOW = 12.hours
        const val MIN_BODY_ALT_DEG = 10.0
        const val MAX_SUN_ALT_DEG = -6.0
    }
}
