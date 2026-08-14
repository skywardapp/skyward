package dev.fritze.skyward.core.visibility

import dev.fritze.skyward.core.astro.altitudeDeg
import dev.fritze.skyward.core.astro.timeSamples
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
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * §8.7 (conjunction half): visible if both bodies are simultaneously above
 * 10 deg altitude with the Sun below -6 deg, at some point within +/-12h of
 * closest approach. No travel concept.
 */
class ConjunctionVisibilityModel : VisibilityModel {
    override val phenomenon = Phenomenon.CONJUNCTION

    override fun evaluate(occ: Occurrence, loc: SavedLocation, ctx: VisibilityContext): VisibilityResult {
        val payload = occ.payload as ConjunctionPayload
        // Persisted as plain strings (§6.4's natural-key stability
        // precedent), not the vendored `Body` enum directly -- guard
        // against a name the enum no longer recognizes (stale/legacy
        // persisted data) rather than crashing the whole planner run.
        val bodyA = Body.entries.find { it.name == payload.body1 }
        val bodyB = Body.entries.find { it.name == payload.body2 }
        if (bodyA == null || bodyB == null) {
            return VisibilityResult(
                visibleAtLocation = false,
                quality = Quality.NONE,
                localDetails = LocalDetails.GenericLocal("${payload.body1}-${payload.body2} conjunction"),
                nearestVisiblePoint = null,
                travelDistanceKm = null,
                travelBearingDeg = null,
                qualityAtNearestPoint = null,
            )
        }

        val observer = Observer(loc.point.latDeg, loc.point.lonDeg, 0.0)
        val start = (payload.timeOfClosest - SEARCH_WINDOW).toAstroTime()
        val end = (payload.timeOfClosest + SEARCH_WINDOW).toAstroTime()
        val visibleAtSomePoint = timeSamples(start, end, SAMPLE_STEP).any { t ->
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
        val roundedSeparationDeg = (payload.minSeparationDeg * 100.0).roundToInt() / 100.0

        return VisibilityResult(
            visibleAtLocation = quality != Quality.NONE,
            quality = quality,
            localDetails = LocalDetails.GenericLocal(
                "${payload.body1}-${payload.body2} separation $roundedSeparationDeg deg",
            ),
            nearestVisiblePoint = null,
            travelDistanceKm = null,
            travelBearingDeg = null,
            qualityAtNearestPoint = null,
        )
    }

    private companion object {
        val SEARCH_WINDOW = 12.hours
        // Finer than the meteor/comet models' hourly cadence: the altitude
        // gates here can clear (or fail) within tens of minutes near
        // rise/set, and this window is only +/-12h wide (vs. an all-night
        // scan), so the extra samples are cheap.
        val SAMPLE_STEP = 15.minutes
        const val MIN_BODY_ALT_DEG = 10.0
        const val MAX_SUN_ALT_DEG = -6.0
    }
}
