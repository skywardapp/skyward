package dev.fritze.skyward.core.sources

import dev.fritze.skyward.core.astro.toAstroTime
import dev.fritze.skyward.core.astro.toInstant
import dev.fritze.skyward.core.model.Certainty
import dev.fritze.skyward.core.model.ConjunctionPayload
import dev.fritze.skyward.core.model.Occurrence
import dev.fritze.skyward.core.model.Phenomenon
import dev.fritze.skyward.core.model.TimeWindow
import io.github.cosinekitty.astronomy.Aberration
import io.github.cosinekitty.astronomy.Body
import io.github.cosinekitty.astronomy.Time
import io.github.cosinekitty.astronomy.angleFromSun
import io.github.cosinekitty.astronomy.geoVector
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/**
 * Geocentric angular separation between two bodies at [t], degrees (§7.6:
 * "geocentric angular separation... spherical law of cosines"). Extracted as
 * a standalone function so it's directly unit-testable against an
 * independent oracle, not just observable via which conjunctions get emitted.
 */
internal fun geocentricSeparationDeg(a: Body, b: Body, t: Time): Double {
    val va = geoVector(a, t, Aberration.Corrected)
    val vb = geoVector(b, t, Aberration.Corrected)
    return va.angleWith(vb)
}

/** §7.6: Moon-planet and planet-planet close approaches. `id = "conjunctions"`, COMPUTED. */
class ConjunctionSource : EventSource {
    override val id = "conjunctions"
    override val phenomena = setOf(Phenomenon.CONJUNCTION)
    override val kind = SourceKind.COMPUTED

    override fun schedule(settings: SourceSettings): Schedule = Schedule.OnHorizonChange

    override suspend fun refresh(req: RefreshRequest): RefreshResult {
        val occurrences = mutableListOf<Occurrence>()
        for (pair in PAIRS) {
            occurrences += findConjunctions(pair, req.horizon.start.toAstroTime(), req.horizon.end.toAstroTime(), req.now)
        }
        return RefreshResult(
            occurrences = occurrences,
            newState = req.state,
            nextRefreshHint = null,
            diagnostics = SourceDiagnostics(ok = true, itemCount = occurrences.size, lastSuccessAt = req.now),
        )
    }

    private fun findConjunctions(pair: BodyPair, start: Time, end: Time, now: Instant): List<Occurrence> {
        val found = mutableListOf<Occurrence>()
        val stepDays = 6.0 / 24.0 // 6-hour steps

        var tPrev = start
        var sepPrev = separationDeg(pair, tPrev)
        var tCurr = start.addDays(stepDays)
        var sepCurr = separationDeg(pair, tCurr)

        while (tCurr.tt < end.tt) {
            val tNext = tCurr.addDays(stepDays)
            val sepNext = separationDeg(pair, tNext)

            if (sepCurr < sepPrev && sepCurr < sepNext && sepCurr < pair.thresholdDeg) {
                val (closestTime, closestSep) = refineMinimum(pair, tPrev, tNext)
                if (closestSep < pair.thresholdDeg && isObservable(pair, closestTime)) {
                    found += buildOccurrence(pair, closestTime, closestSep, now)
                }
            }

            tPrev = tCurr; sepPrev = sepCurr
            tCurr = tNext; sepCurr = sepNext
        }
        return found
    }

    private fun isObservable(pair: BodyPair, t: Time): Boolean =
        angleFromSun(pair.a, t) > MIN_ELONGATION_DEG && angleFromSun(pair.b, t) > MIN_ELONGATION_DEG

    /** Golden-section search for the separation minimum inside `(left, right)`, to about a minute. */
    private fun refineMinimum(pair: BodyPair, left: Time, right: Time): Pair<Time, Double> {
        val goldenRatio = (kotlin.math.sqrt(5.0) - 1.0) / 2.0
        var a = left.tt
        var b = right.tt
        val toleranceDays = 1.0 / 1440.0 // ~1 minute

        var c = b - goldenRatio * (b - a)
        var d = a + goldenRatio * (b - a)
        var fc = separationDeg(pair, Time.fromTerrestrialTime(c))
        var fd = separationDeg(pair, Time.fromTerrestrialTime(d))

        while (b - a > toleranceDays) {
            if (fc < fd) {
                b = d
                d = c; fd = fc
                c = b - goldenRatio * (b - a)
                fc = separationDeg(pair, Time.fromTerrestrialTime(c))
            } else {
                a = c
                c = d; fc = fd
                d = a + goldenRatio * (b - a)
                fd = separationDeg(pair, Time.fromTerrestrialTime(d))
            }
        }
        val tMin = Time.fromTerrestrialTime((a + b) / 2.0)
        return tMin to separationDeg(pair, tMin)
    }

    private fun separationDeg(pair: BodyPair, t: Time): Double = geocentricSeparationDeg(pair.a, pair.b, t)

    private fun buildOccurrence(pair: BodyPair, closestTime: Time, minSeparationDeg: Double, now: Instant): Occurrence {
        val timeOfClosest = closestTime.toInstant()
        val (nameA, nameB) = listOf(pair.a.name, pair.b.name).sorted()
        val dateKey = timeOfClosest.toYearMonthDayKey()
        return Occurrence(
            id = "cj:${nameA.lowercase()}-${nameB.lowercase()}:$dateKey",
            phenomenon = Phenomenon.CONJUNCTION,
            sourceId = id,
            title = "${pair.a.name}-${pair.b.name} conjunction",
            window = TimeWindow(timeOfClosest - 12.hours, timeOfClosest + 12.hours),
            peakTime = timeOfClosest,
            certainty = Certainty.CERTAIN,
            payload = ConjunctionPayload(
                body1 = pair.a.name,
                body2 = pair.b.name,
                minSeparationDeg = minSeparationDeg,
                timeOfClosest = timeOfClosest,
            ),
            fetchedAt = now,
            expiresAt = null,
        )
    }

    private data class BodyPair(val a: Body, val b: Body, val thresholdDeg: Double)

    private companion object {
        const val MIN_ELONGATION_DEG = 15.0
        const val MOON_PLANET_THRESHOLD_DEG = 2.0
        const val PLANET_PLANET_THRESHOLD_DEG = 1.0

        val PLANETS = listOf(Body.Mercury, Body.Venus, Body.Mars, Body.Jupiter, Body.Saturn)

        val PAIRS: List<BodyPair> = buildList {
            for (planet in PLANETS) add(BodyPair(Body.Moon, planet, MOON_PLANET_THRESHOLD_DEG))
            for (i in PLANETS.indices) {
                for (j in i + 1 until PLANETS.size) {
                    add(BodyPair(PLANETS[i], PLANETS[j], PLANET_PLANET_THRESHOLD_DEG))
                }
            }
        }
    }
}
