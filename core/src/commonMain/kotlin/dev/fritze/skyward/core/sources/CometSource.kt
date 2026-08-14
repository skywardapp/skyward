package dev.fritze.skyward.core.sources

import dev.fritze.skyward.core.astro.apparentMagnitude
import dev.fritze.skyward.core.model.Certainty
import dev.fritze.skyward.core.model.CometElements
import dev.fritze.skyward.core.model.CometMagParams
import dev.fritze.skyward.core.model.CometPayload
import dev.fritze.skyward.core.model.Occurrence
import dev.fritze.skyward.core.model.Phenomenon
import dev.fritze.skyward.core.model.TimeWindow
import dev.fritze.skyward.core.net.createHttpClient
import dev.fritze.skyward.core.net.getText
import io.ktor.client.HttpClient
import io.ktor.http.encodeURLQueryComponent
import kotlin.math.sqrt
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/**
 * §7.4: JPL SBDB monthly discovery + on-device propagation atop [core.astro.Kepler]
 * (§7.4.2, already implemented). `id = "jpl"`, POLLED. All the "is this comet
 * even worth tracking" work happens locally after one small monthly fetch --
 * P1's "everything that can be computed on-device is" applies here almost as
 * much as it does to the fully-COMPUTED sources.
 */
class CometSource(private val httpClient: HttpClient = createHttpClient()) : EventSource {
    override val id = "jpl"
    override val phenomena = setOf(Phenomenon.COMET)
    override val kind = SourceKind.POLLED

    override fun schedule(settings: SourceSettings): Schedule = Schedule.Periodic(30.days)

    override suspend fun refresh(req: RefreshRequest): RefreshResult {
        val candidates = parseJplSbdbQuery(httpClient.getText(discoveryUrl()))
        val ingestFloor = maxOf(req.derivedThresholds.maxCometMag ?: 6.0, 6.0)

        var droppedNonConvergent = 0
        val occurrences = mutableListOf<Occurrence>()
        for (candidate in candidates) {
            val occurrence = buildCometOccurrence(candidate, ingestFloor, req.now)
            if (occurrence == null) {
                droppedNonConvergent++
            } else {
                occurrences += occurrence
            }
        }

        val diagnostics = SourceDiagnostics(
            ok = true,
            message = if (droppedNonConvergent > 0) {
                "$droppedNonConvergent comet(s) dropped: propagator did not converge across the scan range"
            } else {
                null
            },
            itemCount = occurrences.size,
            lastSuccessAt = req.now,
        )
        return RefreshResult(occurrences = occurrences, newState = emptyMap(), nextRefreshHint = null, diagnostics = diagnostics)
    }

    /**
     * §7.4.3. Anchored on the orbit (`tp +/- 9 months`), not on `now`, so
     * `peakMagDate` is stable across monthly refreshes -- scanning forward
     * from `now` instead would slide the peak forward every refresh once
     * perihelion has passed, and §6.3 would misread that drift as a
     * material change. Returns `null` only when the propagator never
     * converges anywhere in the scan range (§7.4.2's "drop with a
     * diagnostic rather than emit garbage") -- failing the *ingest floor*
     * is a normal, silent "not bright enough," not an error.
     */
    private fun buildCometOccurrence(candidate: CometCandidate, ingestFloor: Double, now: Instant): Occurrence? {
        val elements = candidate.elements
        val magParams = candidate.magParams
        val tp = elements.tpPerihelion
        val scanStart = minOf(now, tp - NINE_MONTHS)
        val scanEnd = maxOf(now, tp + NINE_MONTHS)

        val samples = mutableListOf<Pair<Instant, Double>>()
        var t = scanStart
        while (t <= scanEnd) {
            apparentMagnitude(elements, magParams, t)?.let { samples += t to it }
            t += 1.days
        }
        if (samples.isEmpty()) return null

        val roughPeak = samples.minBy { it.second }
        if (roughPeak.second > ingestFloor) return null // §7.4.3 ingest filter

        val idx = samples.indexOf(roughPeak)
        val left = samples.getOrNull(idx - 1)?.first ?: (roughPeak.first - 1.days)
        val right = samples.getOrNull(idx + 1)?.first ?: (roughPeak.first + 1.days)
        val (peakMagDate, peakMag) = refineMagnitudeMinimum(elements, magParams, left, right) ?: roughPeak

        var windowStart = roughPeak.first
        for (i in idx downTo 0) {
            if (samples[i].second > ingestFloor) break
            windowStart = samples[i].first
        }
        var windowEnd = roughPeak.first
        for (i in idx until samples.size) {
            if (samples[i].second > ingestFloor) break
            windowEnd = samples[i].first
        }

        // §7.4.3: "the only field that legitimately changes each refresh" --
        // display-only, deliberately excluded from §6.3 materiality (MaterialChange.kt).
        val magAtIngest = apparentMagnitude(elements, magParams, now) ?: peakMag

        return Occurrence(
            id = "comet:${candidate.designation}",
            phenomenon = Phenomenon.COMET,
            sourceId = id,
            title = "Comet ${candidate.name ?: candidate.designation}",
            window = TimeWindow(windowStart, windowEnd + 1.days),
            peakTime = peakMagDate,
            certainty = Certainty.FORECAST,
            payload = CometPayload(
                designation = candidate.designation,
                name = candidate.name,
                elements = elements,
                magParams = magParams,
                perihelionDate = tp,
                peakMag = peakMag,
                peakMagDate = peakMagDate,
                magAtIngest = magAtIngest,
            ),
            fetchedAt = now,
            expiresAt = now + 45.days,
        )
    }

    /** Golden-section search for the magnitude minimum inside `(left, right)`, refined to the hour (§7.4.3) -- same technique as ConjunctionSource's separation-minimum refinement, over `Instant` instead of Astronomy Engine's `Time`. */
    private fun refineMagnitudeMinimum(el: CometElements, mp: CometMagParams, left: Instant, right: Instant): Pair<Instant, Double>? {
        fun magAt(epochSec: Double): Double = apparentMagnitude(el, mp, Instant.fromEpochSeconds(epochSec.toLong())) ?: Double.MAX_VALUE

        val goldenRatio = (sqrt(5.0) - 1.0) / 2.0
        var a = minOf(left.epochSeconds, right.epochSeconds).toDouble()
        var b = maxOf(left.epochSeconds, right.epochSeconds).toDouble()
        val toleranceSeconds = 1.hours.inWholeSeconds.toDouble()

        var c = b - goldenRatio * (b - a)
        var d = a + goldenRatio * (b - a)
        var fc = magAt(c)
        var fd = magAt(d)

        while (b - a > toleranceSeconds) {
            if (fc < fd) {
                b = d; d = c; fd = fc
                c = b - goldenRatio * (b - a)
                fc = magAt(c)
            } else {
                a = c; c = d; fc = fd
                d = a + goldenRatio * (b - a)
                fd = magAt(d)
            }
        }

        val tMin = Instant.fromEpochSeconds(((a + b) / 2.0).toLong())
        val mag = apparentMagnitude(el, mp, tMin) ?: return null
        return tMin to mag
    }

    private fun discoveryUrl(): String {
        val cdata = """{"AND":["q|LT|4.5","M1|LT|14"]}"""
        return "https://ssd-api.jpl.nasa.gov/sbdb_query.api" +
            "?sb-kind=c" +
            "&fields=full_name,pdes,name,epoch,e,q,i,om,w,tp,M1,K1,M2,K2" +
            "&sb-cdata=${cdata.encodeURLQueryComponent()}" +
            "&full-prec=1"
    }

    private companion object {
        // Approximated as a fixed duration rather than calendar months: this
        // only bounds a magnitude scan window, not a displayed date, so
        // day-level slop against a true "9 calendar months" is immaterial.
        val NINE_MONTHS = 274.days
    }
}
