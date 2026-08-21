package dev.fritze.skyward.core.sources

import dev.fritze.skyward.core.astro.subPoint
import dev.fritze.skyward.core.astro.toAstroTime
import dev.fritze.skyward.core.astro.toInstant
import dev.fritze.skyward.core.model.Certainty
import dev.fritze.skyward.core.model.GeoPoint
import dev.fritze.skyward.core.model.LunarEclipseKind
import dev.fritze.skyward.core.model.LunarEclipsePayload
import dev.fritze.skyward.core.model.Occurrence
import dev.fritze.skyward.core.model.PathSample
import dev.fritze.skyward.core.model.Phenomenon
import dev.fritze.skyward.core.model.SolarEclipseKind
import dev.fritze.skyward.core.model.SolarEclipsePayload
import dev.fritze.skyward.core.model.TimeWindow
import dev.fritze.skyward.core.persistence.persistenceJson
import io.github.cosinekitty.astronomy.Body
import io.github.cosinekitty.astronomy.EclipseKind
import io.github.cosinekitty.astronomy.GlobalSolarEclipseInfo
import io.github.cosinekitty.astronomy.LocalSolarEclipseInfo
import io.github.cosinekitty.astronomy.LunarEclipseInfo
import io.github.cosinekitty.astronomy.Observer
import io.github.cosinekitty.astronomy.Time
import io.github.cosinekitty.astronomy.globalSolarEclipsesAfter
import io.github.cosinekitty.astronomy.lunarEclipsesAfter
import io.github.cosinekitty.astronomy.searchLocalSolarEclipse
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * §7.1: solar and lunar eclipses, computed on-device (D9). `id = "eclipse"`,
 * COMPUTED.
 *
 * Vendored Astronomy Engine's [EclipseKind] has no `Hybrid` value — global
 * solar eclipse search only ever reports `Partial`/`Annular`/`Total` — so
 * this source can never emit [SolarEclipseKind.HYBRID]. A real
 * annular-total hybrid eclipse will be reported as `Total` (its majority
 * classification). Out of scope for M1 to fix; noted rather than silently
 * mismodeled.
 */
class EclipseSource : EventSource {
    override val id = "eclipse"
    override val phenomena = setOf(Phenomenon.SOLAR_ECLIPSE, Phenomenon.LUNAR_ECLIPSE)
    override val kind = SourceKind.COMPUTED

    override fun schedule(settings: SourceSettings): Schedule = Schedule.OnHorizonChange

    override suspend fun refresh(req: RefreshRequest): RefreshResult {
        val start = req.horizon.start.toAstroTime()
        val end = req.horizon.end.toAstroTime()
        val occurrences = mutableListOf<Occurrence>()
        val pathCache = PathCache(req.state)

        for (eclipse in globalSolarEclipsesAfter(start)) {
            if (eclipse.peak.tt > end.tt) break
            occurrences += buildSolarOccurrence(eclipse, req.now, pathCache)
        }
        for (eclipse in lunarEclipsesAfter(start)) {
            if (eclipse.peak.tt > end.tt) break
            occurrences += buildLunarOccurrence(eclipse, req.now)
        }

        return RefreshResult(
            occurrences = occurrences,
            newState = pathCache.toState(),
            nextRefreshHint = null,
            diagnostics = SourceDiagnostics(ok = true, itemCount = occurrences.size, lastSuccessAt = req.now),
        )
    }

    /**
     * §7.1.3's "run once per eclipse, cached", made real: the coarse scan is
     * thousands of `searchLocalSolarEclipse` calls per central eclipse — the
     * <60 s desktop / <3 min Android budget is *per eclipse*, and a default
     * 3-year horizon holds several. Without a cache every refresh re-derives
     * a path that is a pure function of the eclipse, which on Android was
     * enough to have WorkManager kill the periodic worker before the polled
     * sources after this one ever ran (issue #49).
     *
     * Keyed by the occurrence's natural key (§6.4), so the cache survives
     * anything that does not change which eclipse a row describes. The
     * sampling parameters are part of the stored fingerprint: tuning the grid
     * or the hill-climb (§7.1.3 calls its numbers "starting values") must
     * invalidate paths computed by the previous tuning rather than leave a
     * user on whatever their first install happened to compute.
     */
    private class PathCache(state: Map<String, ByteArray>) {
        private val stored: Map<String, List<PathSample>> = decode(state)

        /**
         * Only ids passed through here survive into [toState] — an eclipse
         * that has dropped out of the horizon, or is no longer central, takes
         * its cached path with it instead of accumulating forever (the runner
         * upserts `newState` key by key and never deletes, §6.2).
         */
        private val kept = mutableMapOf<String, List<PathSample>>()

        fun getOrCompute(occurrenceId: String, compute: () -> List<PathSample>): List<PathSample> =
            (stored[occurrenceId] ?: compute()).also { kept[occurrenceId] = it }

        fun toState(): Map<String, ByteArray> = mapOf(
            STATE_KEY_PATHS to persistenceJson
                .encodeToString(CachedPaths.serializer(), CachedPaths(ALGORITHM_FINGERPRINT, kept))
                .encodeToByteArray(),
        )

        private companion object {
            fun decode(state: Map<String, ByteArray>): Map<String, List<PathSample>> {
                val raw = state[STATE_KEY_PATHS] ?: return emptyMap()
                // A blob written by a future schema, or a truncated one, costs
                // a recompute — never a failed refresh.
                val cached = runCatching {
                    persistenceJson.decodeFromString(CachedPaths.serializer(), raw.decodeToString())
                }.getOrNull() ?: return emptyMap()
                return if (cached.algorithm == ALGORITHM_FINGERPRINT) cached.paths else emptyMap()
            }
        }
    }

    private fun buildSolarOccurrence(eclipse: GlobalSolarEclipseInfo, now: Instant, pathCache: PathCache): Occurrence {
        val solarKind = when (eclipse.kind) {
            EclipseKind.Partial -> SolarEclipseKind.PARTIAL
            EclipseKind.Annular -> SolarEclipseKind.ANNULAR
            EclipseKind.Total -> SolarEclipseKind.TOTAL
            EclipseKind.Penumbral -> error("global solar eclipse search never returns Penumbral")
        }
        val peakInstant = eclipse.peak.toInstant()
        val occurrenceId = "se:${peakInstant.toYearMonthDayKey()}"
        val centralPath = if (solarKind == SolarEclipseKind.TOTAL || solarKind == SolarEclipseKind.ANNULAR) {
            pathCache.getOrCompute(occurrenceId) { samplePath(eclipse) }
        } else {
            emptyList()
        }
        val greatest = greatestCircumstances(eclipse)

        return Occurrence(
            id = occurrenceId,
            phenomenon = Phenomenon.SOLAR_ECLIPSE,
            sourceId = id,
            title = "${solarKind.name.lowercase().replaceFirstChar { it.uppercase() }} solar eclipse",
            // §7.1.4: approximate with peak +/- 3h since global first/last contact
            // isn't cheaply available from the global search alone.
            window = TimeWindow(peakInstant - 3.hours, peakInstant + 3.hours),
            peakTime = peakInstant,
            certainty = Certainty.CERTAIN,
            payload = SolarEclipsePayload(
                kind = solarKind,
                greatestEclipsePoint = greatest.point,
                greatestEclipseTime = peakInstant,
                centralPath = centralPath,
                obscurationAtGreatest = greatest.obscuration,
            ),
            fetchedAt = now,
            expiresAt = null,
        )
    }

    private data class GreatestCircumstances(val point: GeoPoint, val obscuration: Double)

    /**
     * Astronomy Engine reports `latitude`, `longitude` and `obscuration` as
     * **NaN** for a PARTIAL global eclipse: the shadow axis misses the Earth
     * entirely, so there is no "center of the peak eclipse shadow" to report.
     * §7.1.4 still wants one `Occurrence` per eclipse with a point, and a NaN
     * one is not merely imprecise — it cannot be serialized into
     * `payload_json` at all (JSON has no NaN, §11), and it makes §8.2's
     * travel search start from nowhere.
     *
     * For that case the sub-lunar point stands in: at eclipse time the Moon
     * and the shadow axis lie in nearly the same direction from the Earth's
     * centre, so the point with the Moon overhead is, to well within the
     * precision this seeds, the point on Earth closest to the axis — i.e.
     * where the eclipse is deepest. Its local obscuration is then a real
     * measured value rather than a missing one.
     */
    private fun greatestCircumstances(eclipse: GlobalSolarEclipseInfo): GreatestCircumstances {
        val searchStart = eclipse.peak.addDays(-4.0 / 24.0)
        if (eclipse.latitude.isFinite() && eclipse.longitude.isFinite()) {
            val point = GeoPoint(eclipse.latitude, eclipse.longitude)
            val obscuration = eclipse.obscuration.takeIf { it.isFinite() }
                ?: tryLocalEclipse(point.latDeg, point.lonDeg, searchStart)?.obscuration?.takeIf { it.isFinite() }
                ?: 1.0 // total/annular by definition once the axis reaches the ground
            return GreatestCircumstances(point, obscuration)
        }

        val subLunar = subPoint(Body.Moon, eclipse.peak)
        val local = tryLocalEclipse(subLunar.latDeg, subLunar.lonDeg, searchStart)
        return GreatestCircumstances(
            point = subLunar,
            // 0.0 only if even the local search fails — an honest "we could not
            // determine how deep it gets", not a claim that nothing happens.
            obscuration = local?.obscuration?.takeIf { it.isFinite() } ?: 0.0,
        )
    }

    private fun buildLunarOccurrence(eclipse: LunarEclipseInfo, now: Instant): Occurrence {
        val lunarKind = when (eclipse.kind) {
            EclipseKind.Penumbral -> LunarEclipseKind.PENUMBRAL
            EclipseKind.Partial -> LunarEclipseKind.PARTIAL
            EclipseKind.Total -> LunarEclipseKind.TOTAL
            EclipseKind.Annular -> error("lunar eclipse search never returns Annular")
        }
        val peak = eclipse.peak.toInstant()
        val penumbralBegin = peak - eclipse.sdPenum.minutes
        val penumbralEnd = peak + eclipse.sdPenum.minutes
        val partialBegin = if (eclipse.sdPartial > 0.0) peak - eclipse.sdPartial.minutes else null
        val partialEnd = if (eclipse.sdPartial > 0.0) peak + eclipse.sdPartial.minutes else null
        val totalBegin = if (eclipse.sdTotal > 0.0) peak - eclipse.sdTotal.minutes else null
        val totalEnd = if (eclipse.sdTotal > 0.0) peak + eclipse.sdTotal.minutes else null

        return Occurrence(
            id = "le:${peak.toYearMonthDayKey()}",
            phenomenon = Phenomenon.LUNAR_ECLIPSE,
            sourceId = id,
            title = "${lunarKind.name.lowercase().replaceFirstChar { it.uppercase() }} lunar eclipse",
            window = TimeWindow(penumbralBegin, penumbralEnd),
            peakTime = peak,
            certainty = Certainty.CERTAIN,
            payload = LunarEclipsePayload(
                kind = lunarKind,
                penumbralBegin = penumbralBegin,
                partialBegin = partialBegin,
                totalBegin = totalBegin,
                totalEnd = totalEnd,
                partialEnd = partialEnd,
                penumbralEnd = penumbralEnd,
            ),
            fetchedAt = now,
            expiresAt = null,
        )
    }

    // ---- §7.1.3: central path sampling ------------------------------------

    private fun samplePath(eclipse: GlobalSolarEclipseInfo): List<PathSample> {
        val kept = coarseScan(eclipse)
        if (kept.isEmpty()) return emptyList()

        val ordered = kept.sortedBy { it.local.peak.time.tt }
        val buckets = ordered.groupBy { bucketIndex(it.local.peak.time) }

        return buckets.entries
            .sortedBy { it.key }
            .mapNotNull { (_, pointsInBucket) -> refineBucket(pointsInBucket) }
            // Refinement moves a sample off its bucket's nominal time, which
            // can reorder neighbours across a bucket boundary. Sort by the
            // *emitted* time: the list order is the polyline order the map
            // draws (§14.1), and the shadow's track is by definition the
            // order in which it touches the ground.
            .sortedBy { it.time }
            // Belt and braces: two adjacent buckets whose refinements converge
            // on the same point would draw as a zero-length segment on the map
            // and skew the path's own duration statistics. The bounded
            // refinement below is what actually prevents this; this keeps a
            // future tuning change from silently reintroducing it.
            .distinctBy { it.time }
    }

    private data class GridHit(val point: GeoPoint, val local: LocalSolarEclipseInfo)

    private fun coarseScan(eclipse: GlobalSolarEclipseInfo): List<GridHit> {
        val hits = mutableListOf<GridHit>()
        val searchStart = eclipse.peak.addDays(-4.0 / 24.0)
        // A total/annular central path stays close to the greatest-eclipse
        // latitude (eclipse.latitude is already known from the global search)
        // — scanning the full -85..85 band for every eclipse is ~9,900
        // searchLocalSolarEclipse calls per eclipse for no benefit.
        // The +-85 clamp keeps the scan out of the grid's polar singularity,
        // at the cost of not tracing the polar cap of a path that reaches it
        // (2026-08-12 tops out at 89.1 N, so its track above 85 N is a hole).
        // docs/adr/0013-eclipse-path-sample-spacing.md records the effect on
        // sample spacing and why raising it is a deliberate §7.1.3 decision
        // rather than a tweak.
        val latMin = (eclipse.latitude - LAT_BAND_DEG).coerceAtLeast(-85.0)
        val latMax = (eclipse.latitude + LAT_BAND_DEG).coerceAtMost(85.0)
        var lat = latMin
        while (lat <= latMax) {
            var lon = -180.0
            while (lon < 180.0) {
                val local = tryLocalEclipse(lat, lon, searchStart)
                if (local != null && isCentralPathPoint(local) && isNearGlobalPeak(local, eclipse)) {
                    hits += GridHit(GeoPoint(lat, lon), local)
                }
                lon += COARSE_GRID_STEP_DEG
            }
            lat += COARSE_GRID_STEP_DEG
        }
        return hits
    }

    /** Kind + central-phase + above-horizon check — independent of which eclipse this is. */
    private fun isCentralPathPoint(local: LocalSolarEclipseInfo): Boolean =
        (local.kind == EclipseKind.Total || local.kind == EclipseKind.Annular) &&
            local.totalBegin != null && local.totalEnd != null &&
            local.peak.altitude > 0.0

    /** Only meaningful in the coarse scan, to reject a *different* eclipse's path. */
    private fun isNearGlobalPeak(local: LocalSolarEclipseInfo, eclipse: GlobalSolarEclipseInfo): Boolean =
        abs(local.peak.time.tt - eclipse.peak.tt) <= 4.0 / 24.0

    private fun tryLocalEclipse(lat: Double, lon: Double, searchStart: Time): LocalSolarEclipseInfo? =
        runCatching { searchLocalSolarEclipse(searchStart, Observer(lat, lon, 0.0)) }.getOrNull()

    /** 2-minute local-peak-time slot index (§7.1.3 step 3). */
    private fun bucketIndex(t: Time): Long = (t.tt * MINUTES_PER_DAY / BUCKET_MINUTES).toLong()

    /**
     * §7.1.3 step 3: "refine the centroid point with 4 rounds of ±step/2
     * hill-climbing (step 2.5°→0.15°) maximizing central duration ... the
     * maximum lies on the centerline."
     *
     * The refinement is deliberately **bounded**: each round probes exactly
     * once at ±`offset` and then halves `offset`, so a sample can move at
     * most 1.25 + 0.625 + 0.3125 + 0.156 ≈ 2.34° from its bucket's centroid.
     * That bound is the whole point. Central duration rises monotonically
     * along the path toward greatest eclipse, so an *unbounded* climb doesn't
     * find this bucket's centreline point — it walks off down the path to the
     * global maximum, and every bucket collapses onto the same handful of
     * points (which is exactly what the §18 M6 map-rendering check caught:
     * a 2027-08-02 "path" of a dozen repeated coordinates with southern Spain
     * and Morocco missing entirely).
     */
    private fun refineBucket(pointsInBucket: List<GridHit>): PathSample? {
        val searchStart = pointsInBucket.first().local.peak.time.addDays(-4.0 / 24.0)
        var lat = pointsInBucket.map { it.point.latDeg }.average()
        // Circular (not arithmetic) mean: a bucket whose grid hits straddle
        // the antimeridian (e.g. -179 and 179) must average to ~180, not ~0.
        var lon = circularMeanDeg(pointsInBucket.map { it.point.lonDeg })

        // Start from the centroid itself where it is genuinely on the central
        // path; the bucket's own best grid hit is the fallback when the
        // centroid of several hits lands just off it.
        val atCentroid = tryLocalEclipse(lat, normalizeLonDeg(lon), searchStart)?.takeIf(::isCentralPathPoint)
        var best: LocalSolarEclipseInfo
        if (atCentroid != null) {
            best = atCentroid
        } else {
            val bestHit = pointsInBucket.maxBy { centralDurationDays(it.local) }
            best = bestHit.local
            lat = bestHit.point.latDeg
            lon = bestHit.point.lonDeg
        }

        var offset = COARSE_GRID_STEP_DEG / 2.0
        repeat(HILL_CLIMB_ROUNDS) {
            var bestDuration = centralDurationDays(best)
            var bestLat = lat
            var bestLon = lon
            var bestLocal = best
            for (dLat in doubleArrayOf(-offset, 0.0, offset)) {
                for (dLon in doubleArrayOf(-offset, 0.0, offset)) {
                    if (dLat == 0.0 && dLon == 0.0) continue
                    val candidateLat = (lat + dLat).coerceIn(-90.0, 90.0)
                    val candidateLon = normalizeLonDeg(lon + dLon)
                    val candidate = tryLocalEclipse(candidateLat, candidateLon, searchStart) ?: continue
                    if (!isCentralPathPoint(candidate)) continue
                    val duration = centralDurationDays(candidate)
                    if (duration > bestDuration) {
                        bestDuration = duration
                        bestLocal = candidate
                        bestLat = candidateLat
                        bestLon = candidateLon
                    }
                }
            }
            lat = bestLat
            lon = bestLon
            best = bestLocal
            offset /= 2.0
        }

        val totalBegin = best.totalBegin ?: return null
        val totalEnd = best.totalEnd ?: return null
        val durationSec = (totalEnd.time.tt - totalBegin.time.tt) * 86_400.0

        return PathSample(
            time = best.peak.time.toInstant(),
            point = GeoPoint(lat, normalizeLonDeg(lon)),
            pathWidthKm = null, // §7.1.3: acceptable in v1; visibility uses the +/-edge search (§8.2)
            centralDurationSec = durationSec,
        )
    }

    private fun circularMeanDeg(lonsDeg: List<Double>): Double {
        val sinSum = lonsDeg.sumOf { sin(it.toRadiansLocal()) }
        val cosSum = lonsDeg.sumOf { cos(it.toRadiansLocal()) }
        return atan2(sinSum, cosSum).toDegreesLocal()
    }

    /** Normalizes to `[-180, 180)`, matching [GeoPoint.lonDeg]'s documented range. */
    private fun normalizeLonDeg(lonDeg: Double): Double = ((lonDeg + 540.0) % 360.0) - 180.0

    private fun Double.toRadiansLocal() = this * kotlin.math.PI / 180.0
    private fun Double.toDegreesLocal() = this * 180.0 / kotlin.math.PI

    /** In days (`Time.tt` units) — fine for the hill-climb's relative comparisons; see [refineBucket] for the seconds conversion. */
    private fun centralDurationDays(local: LocalSolarEclipseInfo): Double {
        val b = local.totalBegin ?: return 0.0
        val e = local.totalEnd ?: return 0.0
        return e.time.tt - b.time.tt
    }

    private companion object {
        const val COARSE_GRID_STEP_DEG = 2.5
        const val HILL_CLIMB_ROUNDS = 4
        const val BUCKET_MINUTES = 2.0
        const val MINUTES_PER_DAY = 1440.0
        const val LAT_BAND_DEG = 60.0

        /** `source_state` key (§11) holding every in-horizon central path. */
        const val STATE_KEY_PATHS = "central_paths_json"

        /**
         * Every tuning knob [samplePath] reads, so that changing one
         * invalidates the paths the previous tuning produced — see
         * [PathCache]. The leading number covers a change to the sampling
         * *algorithm* that leaves the knobs alone.
         */
        const val ALGORITHM_FINGERPRINT = "1:$COARSE_GRID_STEP_DEG:$HILL_CLIMB_ROUNDS:$BUCKET_MINUTES:$LAT_BAND_DEG"
    }
}

/** Persisted shape of [EclipseSource]'s central-path cache. */
@Serializable
private data class CachedPaths(
    val algorithm: String,
    val paths: Map<String, List<PathSample>>,
)
