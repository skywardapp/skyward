package dev.fritze.skyward.core.sources

import dev.fritze.skyward.core.astro.toAstroTime
import dev.fritze.skyward.core.astro.toInstant
import dev.fritze.skyward.core.model.Certainty
import dev.fritze.skyward.core.model.MoonEventKind
import dev.fritze.skyward.core.model.MoonEventPayload
import dev.fritze.skyward.core.model.Occurrence
import dev.fritze.skyward.core.model.Phenomenon
import dev.fritze.skyward.core.model.TimeWindow
import io.github.cosinekitty.astronomy.ApsisInfo
import io.github.cosinekitty.astronomy.ApsisKind
import io.github.cosinekitty.astronomy.Time
import io.github.cosinekitty.astronomy.nextLunarApsis
import io.github.cosinekitty.astronomy.searchLunarApsis
import io.github.cosinekitty.astronomy.searchMoonPhase
import kotlin.math.abs
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/** §7.5: supermoons. `id = "moon"`, COMPUTED. */
class MoonEventSource : EventSource {
    override val id = "moon"
    override val phenomena = setOf(Phenomenon.MOON_EVENT)
    override val kind = SourceKind.COMPUTED

    override fun schedule(settings: SourceSettings): Schedule = Schedule.OnHorizonChange

    override suspend fun refresh(req: RefreshRequest): RefreshResult {
        val occurrences = mutableListOf<Occurrence>()
        var searchFrom = req.horizon.start.toAstroTime()
        val horizonEnd = req.horizon.end.toAstroTime()

        while (true) {
            val fullMoon = searchMoonPhase(180.0, searchFrom, 40.0) ?: break
            if (fullMoon.tt > horizonEnd.tt) break

            val perigee = nearestPerigee(fullMoon)
            val fullMoonInstant = fullMoon.toInstant()
            val perigeeInstant = perigee.time.toInstant()
            val hoursApart = abs((fullMoonInstant - perigeeInstant).inWholeMinutes) / 60.0

            if (hoursApart <= 24.0 && perigee.distanceKm < SUPERMOON_MAX_DISTANCE_KM) {
                occurrences += buildOccurrence(fullMoonInstant, perigeeInstant, perigee.distanceKm, req.now)
            }

            searchFrom = fullMoon.addDays(1.0)
        }

        return RefreshResult(
            occurrences = occurrences,
            newState = req.state,
            nextRefreshHint = null,
            diagnostics = SourceDiagnostics(ok = true, itemCount = occurrences.size, lastSuccessAt = req.now),
        )
    }

    /** Searches outward from [around] for the [ApsisKind.Pericenter] event closest in time to it. */
    private fun nearestPerigee(around: Time): ApsisInfo {
        // Anomalistic month is ~27.55 days, so a perigee is guaranteed within a
        // ~20-day window either side of any starting point.
        var apsis = searchLunarApsis(around.addDays(-20.0))
        var best: ApsisInfo? = null
        var bestDelta = Double.MAX_VALUE
        while (apsis.time.tt < around.addDays(20.0).tt) {
            if (apsis.kind == ApsisKind.Pericenter) {
                val delta = abs(apsis.time.tt - around.tt)
                if (delta < bestDelta) {
                    bestDelta = delta
                    best = apsis
                }
            }
            apsis = nextLunarApsis(apsis)
        }
        return checkNotNull(best) { "no perigee found within 20 days of ${around.toInstant()}" }
    }

    private fun buildOccurrence(fullMoonTime: Instant, perigeeTime: Instant, perigeeDistanceKm: Double, now: Instant): Occurrence {
        return Occurrence(
            // §6.4 specifies `sm:<yyyymm>` (month only), but a "blue moon"
            // month can contain two full moons that both qualify as
            // supermoons (confirmed empirically: 2023-08-01 and 2023-08-31
            // both do) — see docs/adr/0002-supermoon-natural-key.md. Day
            // granularity keeps the key deterministic and collision-free.
            id = "sm:${fullMoonTime.toYearMonthDayKey()}",
            phenomenon = Phenomenon.MOON_EVENT,
            sourceId = id,
            title = "Supermoon",
            window = TimeWindow(fullMoonTime - 12.hours, fullMoonTime + 12.hours),
            peakTime = fullMoonTime,
            certainty = Certainty.CERTAIN,
            payload = MoonEventPayload(
                kind = MoonEventKind.SUPERMOON,
                fullMoonTime = fullMoonTime,
                perigeeTime = perigeeTime,
                perigeeDistanceKm = perigeeDistanceKm,
            ),
            fetchedAt = now,
            expiresAt = null,
        )
    }

    private companion object {
        const val SUPERMOON_MAX_DISTANCE_KM = 360_000.0
    }
}
