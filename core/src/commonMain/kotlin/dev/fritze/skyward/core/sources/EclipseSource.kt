package dev.fritze.skyward.core.sources

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
import io.github.cosinekitty.astronomy.EclipseKind
import io.github.cosinekitty.astronomy.GlobalSolarEclipseInfo
import io.github.cosinekitty.astronomy.LocalSolarEclipseInfo
import io.github.cosinekitty.astronomy.LunarEclipseInfo
import io.github.cosinekitty.astronomy.Observer
import io.github.cosinekitty.astronomy.Time
import io.github.cosinekitty.astronomy.globalSolarEclipsesAfter
import io.github.cosinekitty.astronomy.lunarEclipsesAfter
import io.github.cosinekitty.astronomy.searchLocalSolarEclipse
import kotlin.math.abs
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

        for (eclipse in globalSolarEclipsesAfter(start)) {
            if (eclipse.peak.tt > end.tt) break
            occurrences += buildSolarOccurrence(eclipse, req.now)
        }
        for (eclipse in lunarEclipsesAfter(start)) {
            if (eclipse.peak.tt > end.tt) break
            occurrences += buildLunarOccurrence(eclipse, req.now)
        }

        return RefreshResult(
            occurrences = occurrences,
            newState = req.state,
            nextRefreshHint = null,
            diagnostics = SourceDiagnostics(ok = true, itemCount = occurrences.size, lastSuccessAt = req.now),
        )
    }

    private fun buildSolarOccurrence(eclipse: GlobalSolarEclipseInfo, now: Instant): Occurrence {
        val solarKind = when (eclipse.kind) {
            EclipseKind.Partial -> SolarEclipseKind.PARTIAL
            EclipseKind.Annular -> SolarEclipseKind.ANNULAR
            EclipseKind.Total -> SolarEclipseKind.TOTAL
            EclipseKind.Penumbral -> error("global solar eclipse search never returns Penumbral")
        }
        val peakInstant = eclipse.peak.toInstant()
        val centralPath = if (solarKind == SolarEclipseKind.TOTAL || solarKind == SolarEclipseKind.ANNULAR) {
            samplePath(eclipse)
        } else {
            emptyList()
        }

        return Occurrence(
            id = "se:${peakInstant.toYearMonthDayKey()}",
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
                greatestEclipsePoint = GeoPoint(eclipse.latitude, eclipse.longitude),
                greatestEclipseTime = peakInstant,
                centralPath = centralPath,
                obscurationAtGreatest = eclipse.obscuration,
            ),
            fetchedAt = now,
            expiresAt = null,
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
    }

    private data class GridHit(val point: GeoPoint, val local: LocalSolarEclipseInfo)

    private fun coarseScan(eclipse: GlobalSolarEclipseInfo): List<GridHit> {
        val hits = mutableListOf<GridHit>()
        val searchStart = eclipse.peak.addDays(-4.0 / 24.0)
        var lat = -85.0
        while (lat <= 85.0) {
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

    private fun refineBucket(pointsInBucket: List<GridHit>): PathSample? {
        val searchStart = pointsInBucket.first().local.peak.time.addDays(-4.0 / 24.0)
        var lat = pointsInBucket.map { it.point.latDeg }.average()
        var lon = pointsInBucket.map { it.point.lonDeg }.average()
        var best = pointsInBucket.first().local

        var step = COARSE_GRID_STEP_DEG
        repeat(HILL_CLIMB_ROUNDS) {
            var improved = true
            while (improved) {
                improved = false
                var bestDuration = centralDurationDays(best)
                for (dLat in doubleArrayOf(-step, 0.0, step)) {
                    for (dLon in doubleArrayOf(-step, 0.0, step)) {
                        if (dLat == 0.0 && dLon == 0.0) continue
                        val candidateLat = (lat + dLat).coerceIn(-90.0, 90.0)
                        val candidateLon = lon + dLon
                        val candidate = tryLocalEclipse(candidateLat, candidateLon, searchStart) ?: continue
                        if (!isCentralPathPoint(candidate)) continue
                        val duration = centralDurationDays(candidate)
                        if (duration > bestDuration) {
                            bestDuration = duration
                            best = candidate
                            lat = candidateLat
                            lon = candidateLon
                            improved = true
                        }
                    }
                }
            }
            step /= 2.0
        }

        val totalBegin = best.totalBegin ?: return null
        val totalEnd = best.totalEnd ?: return null
        val durationSec = (totalEnd.time.tt - totalBegin.time.tt) * 86_400.0

        return PathSample(
            time = best.peak.time.toInstant(),
            point = GeoPoint(lat, lon),
            pathWidthKm = null, // §7.1.3: acceptable in v1; visibility uses the +/-edge search (§8.2)
            centralDurationSec = durationSec,
        )
    }

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
    }
}
