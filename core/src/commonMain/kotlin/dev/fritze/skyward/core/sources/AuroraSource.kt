package dev.fritze.skyward.core.sources

import dev.fritze.skyward.core.model.AuroraForecastKind
import dev.fritze.skyward.core.model.AuroraPayload
import dev.fritze.skyward.core.model.Certainty
import dev.fritze.skyward.core.model.GeoPoint
import dev.fritze.skyward.core.model.Occurrence
import dev.fritze.skyward.core.model.Phenomenon
import dev.fritze.skyward.core.model.SavedLocation
import dev.fritze.skyward.core.model.TimeWindow
import dev.fritze.skyward.core.net.createHttpClient
import dev.fritze.skyward.core.net.getText
import dev.fritze.skyward.core.persistence.SourceStateRepo
import dev.fritze.skyward.core.visibility.OvationGrid
import dev.fritze.skyward.core.visibility.haversineDistanceKm
import dev.fritze.skyward.core.visibility.toRadians
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlin.math.cos
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * §7.3: NOAA SWPC 3-day Kp forecast (THREE_DAY occurrences) + OVATION aurora
 * nowcast (NOWCAST occurrences), tiered polling. `id = "swpc"`, POLLED.
 *
 * Statelessly re-derives its own tier every call rather than persisting one
 * (§7.3.2): the 3-day forecast is cheap and fetched every refresh regardless
 * of tier, so the "should OVATION be polled *this* call" and "when should
 * the *next* call happen" decisions can both be made fresh from that same
 * freshly-fetched forecast -- no separate tier flag to keep in sync, and a
 * fixture that suddenly shows high Kp goes straight to NOWCAST in the very
 * refresh that saw it (the §18 accept criterion: "within one poll cycle"),
 * not one cycle later.
 */
class AuroraSource(private val httpClient: HttpClient = createHttpClient()) : EventSource {
    override val id = SOURCE_ID
    override val phenomena = setOf(Phenomenon.AURORA)
    override val kind = SourceKind.POLLED

    /** Documents the intended cadence; actual scheduling is driven entirely by [RefreshResult.nextRefreshHint] below. */
    override fun schedule(settings: SourceSettings): Schedule = Schedule.Tiered(active = ACTIVE_INTERVAL, idle = IDLE_INTERVAL)

    override suspend fun refresh(req: RefreshRequest): RefreshResult {
        // Required for THREE_DAY occurrences and the tier decision itself -- if
        // this fails, let the exception propagate so SourceRunner backs off
        // the whole source (§6.2); there's nothing useful to do without it.
        val slots = parseSwpcKpForecast(httpClient.getText(KP_FORECAST_URL))

        val maxKpNext48h = slots
            // A slot's *start* being in the past doesn't mean it's over --
            // include the slot currently in progress (its window still
            // covers req.now), or the tier decision misses ongoing activity
            // for up to a full SLOT_DURATION.
            .filter { it.time + SLOT_DURATION > req.now && it.time <= req.now + 48.hours }
            .maxOfOrNull { it.kp } ?: 0.0
        val thresholdKp = req.derivedThresholds.minKpOfInterest
        // No enabled rule cares about Kp at all -> nothing to compare against;
        // stay idle rather than guess (a KpAtLeast-less "aurora now" rule alone
        // is a known edge case this simplification accepts, §18).
        val goActive = thresholdKp != null && maxKpNext48h >= thresholdKp

        val occurrences = mutableListOf<Occurrence>()
        occurrences += buildThreeDayOccurrences(slots, thresholdKp, req.now)

        val newState = mutableMapOf<String, ByteArray>()
        var ok = true
        var message: String? = null

        if (goActive) {
            try {
                val parsedGrid = parseOvationGridJson(httpClient.getText(OVATION_URL))
                val grid = OvationGrid(parsedGrid.observationTime, parsedGrid.forecastTime, parsedGrid.probBytes)
                if (anyLocationNearNowcastActivity(grid, req.locations)) {
                    occurrences += buildNowcastOccurrence(parsedGrid, maxKpNext48h, req.now)
                }
                newState[STATE_KEY_GRID] = gzipCompress(parsedGrid.probBytes)
                newState[STATE_KEY_FORECAST_TIME] = parsedGrid.forecastTime.toString().encodeToByteArray()
                newState[STATE_KEY_OBSERVATION_TIME] = parsedGrid.observationTime.toString().encodeToByteArray()
                if (parsedGrid.cellsParsed < GRID_CELL_COUNT) {
                    message = "OVATION grid partially parsed (${parsedGrid.cellsParsed}/$GRID_CELL_COUNT cells)"
                }
            } catch (e: Exception) {
                // §19 R3: don't fail the whole refresh -- THREE_DAY occurrences
                // above are still good, and the previously persisted grid (if
                // any) is left untouched since we simply don't overwrite its keys.
                ok = false
                message = "OVATION fetch/parse failed: ${e.message ?: e::class.simpleName}"
            }
        }

        val nextRefreshHint = req.now + if (goActive) ACTIVE_INTERVAL else IDLE_INTERVAL
        return RefreshResult(
            occurrences = occurrences,
            newState = newState,
            nextRefreshHint = nextRefreshHint,
            // `lastSuccessAt` is stamped by SourceRunner.persistRunnerState only
            // when `ok` is true; leaving it null here on the OVATION-partial-
            // failure path avoids reporting a success timestamp for a run that
            // just failed, and avoids clobbering the real previous success time.
            diagnostics = SourceDiagnostics(ok = ok, message = message, itemCount = occurrences.size, lastSuccessAt = if (ok) req.now else null),
        )
    }

    private fun buildThreeDayOccurrences(slots: List<KpSlot>, thresholdKp: Double?, now: Instant): List<Occurrence> =
        slots.mapNotNull { slot ->
            val window = TimeWindow(slot.time, slot.time + SLOT_DURATION)
            if (window.end <= now) return@mapNotNull null // already elapsed
            if (thresholdKp != null && slot.kp < thresholdKp) return@mapNotNull null // §7.3.3: below every threshold -> nothing
            Occurrence(
                id = "au:3d:${slot.time.toYearMonthDayKey()}:${slotLabel(slot.time)}",
                phenomenon = Phenomenon.AURORA,
                sourceId = id,
                title = "Aurora forecast (Kp ${slot.kp})",
                window = window,
                peakTime = slot.time + (SLOT_DURATION / 2),
                certainty = Certainty.FORECAST,
                payload = AuroraPayload(kpForecast = slot.kp, forecastKind = AuroraForecastKind.THREE_DAY, issuedAt = now),
                fetchedAt = now,
                // §7.3.3: "next forecast issue + 6h" -- SWPC updates roughly
                // daily; approximated as fetch time + ~1 day + margin rather
                // than guessing the provider's exact next-issue timestamp.
                expiresAt = now + NEXT_ISSUE_APPROXIMATION,
            )
        }

    private fun buildNowcastOccurrence(parsed: ParsedOvationGrid, maxKpNext48h: Double, now: Instant): Occurrence {
        val window = TimeWindow(parsed.forecastTime, parsed.forecastTime + 1.hours)
        return Occurrence(
            // Time-varying id, deliberately: §7.3.3 says "one occurrence per
            // OVATION fetch" -- each fetch is its own identity, not a row that
            // gets revised in place. That guarantees upsertOccurrences (§6.3)
            // always sees this as new (previous == null) and always triggers
            // a re-plan while active, so the freshly-fetched grid (which lives
            // in source_state, outside this payload) always gets re-consulted
            // -- unlike THREE_DAY, materiality here can't be judged from the
            // payload's own fields alone.
            id = "au:now:${parsed.forecastTime.toEpochMilliseconds()}",
            phenomenon = Phenomenon.AURORA,
            sourceId = id,
            title = "Aurora nowcast",
            window = window,
            peakTime = parsed.forecastTime,
            certainty = Certainty.FORECAST,
            payload = AuroraPayload(kpForecast = maxKpNext48h, forecastKind = AuroraForecastKind.NOWCAST, issuedAt = parsed.forecastTime),
            fetchedAt = now,
            expiresAt = now + 2.hours,
        )
    }

    /** §7.3.3's ingest pre-filter: at least one saved location within 800km of a >=10% cell (the visibility model's own travel-scan radius, §8.4). */
    private fun anyLocationNearNowcastActivity(grid: OvationGrid, locations: List<SavedLocation>): Boolean =
        locations.any { hasActivityWithin(grid, it.point, radiusKm = NOWCAST_PREFILTER_RADIUS_KM, minProbability = NOWCAST_PREFILTER_MIN_PROBABILITY) }

    private fun hasActivityWithin(grid: OvationGrid, center: GeoPoint, radiusKm: Double, minProbability: Int): Boolean {
        if (grid.probabilityAt(center) >= minProbability) return true
        val latSpan = (radiusKm / KM_PER_DEGREE).toInt() + 1
        for (dLat in -latSpan..latSpan) {
            val lat = center.latDeg + dLat
            if (lat < -90.0 || lat > 90.0) continue // skip rather than clamp -- clamping repeats the same polar row redundantly
            val cosLat = cos(lat.toRadians()).coerceAtLeast(0.05)
            val lonSpan = (radiusKm / (KM_PER_DEGREE * cosLat)).toInt() + 1
            for (dLon in -lonSpan..lonSpan) {
                val lon = ((center.lonDeg + dLon + 540.0) % 360.0) - 180.0
                val candidate = GeoPoint(lat, lon)
                if (haversineDistanceKm(center, candidate) > radiusKm) continue
                if (grid.probabilityAt(candidate) >= minProbability) return true
            }
        }
        return false
    }

    private fun slotLabel(time: Instant): String {
        val hourOfDay = ((time.epochSeconds / 3600) % 24 + 24) % 24
        return hourOfDay.toString().padStart(2, '0')
    }

    companion object {
        private const val KP_FORECAST_URL = "https://services.swpc.noaa.gov/products/noaa-planetary-k-index-forecast.json"
        private const val OVATION_URL = "https://services.swpc.noaa.gov/json/ovation_aurora_latest.json"

        private val ACTIVE_INTERVAL = 15.minutes
        private val IDLE_INTERVAL = 3.hours
        private val SLOT_DURATION = 3.hours
        private val NEXT_ISSUE_APPROXIMATION = 30.hours

        private const val KM_PER_DEGREE = 111.2
        private const val NOWCAST_PREFILTER_RADIUS_KM = 800.0
        private const val NOWCAST_PREFILTER_MIN_PROBABILITY = 10
        private const val GRID_CELL_COUNT = 360 * 181

        internal const val STATE_KEY_GRID = "ovation_grid"
        internal const val STATE_KEY_FORECAST_TIME = "ovation_time"
        internal const val STATE_KEY_OBSERVATION_TIME = "ovation_observation_time"

        /** Reconstructs the last persisted OVATION grid, if any -- wired into [dev.fritze.skyward.core.planner.ReplanCoordinator]'s `ovationGridProvider`. */
        suspend fun loadOvationGrid(sourceStateRepo: SourceStateRepo): OvationGrid? {
            val gridBytes = sourceStateRepo.getValue(SOURCE_ID, STATE_KEY_GRID) ?: return null
            val forecastTimeBytes = sourceStateRepo.getValue(SOURCE_ID, STATE_KEY_FORECAST_TIME) ?: return null
            val observationTimeBytes = sourceStateRepo.getValue(SOURCE_ID, STATE_KEY_OBSERVATION_TIME) ?: return null
            return runCatching {
                OvationGrid(
                    observationTime = Instant.parse(observationTimeBytes.decodeToString()),
                    forecastTime = Instant.parse(forecastTimeBytes.decodeToString()),
                    prob = gzipDecompress(gridBytes),
                )
            }.getOrNull()
        }

        /**
         * §7.3.2: the active tier is entered when the forecast warrants it
         * "**or the user opens the aurora dashboard**". The refresh cycle can
         * only act on the first half of that — it has no idea what is on
         * screen — so this is the second half: fetch the OVATION grid now and
         * persist it under the same `source_state` keys [loadOvationGrid]
         * reads, leaving the polling schedule alone.
         *
         * Returns null if the fetch or parse fails; §14.4's dashboard simply
         * keeps showing whatever grid it already had, which is the same
         * degradation [refresh] applies on an OVATION failure (§19 R3).
         */
        suspend fun fetchOvationGridNow(
            sourceStateRepo: SourceStateRepo,
            now: Instant,
            // Nullable rather than a `createHttpClient()` default: a client
            // created here owns an engine with its own threads and selector, and
            // must be closed. A caller-provided one stays the caller's to close.
            httpClient: HttpClient? = null,
        ): OvationGrid? {
            val client = httpClient ?: createHttpClient()
            return try {
                val parsed = parseOvationGridJson(client.getText(OVATION_URL))
                sourceStateRepo.upsert(SOURCE_ID, STATE_KEY_GRID, gzipCompress(parsed.probBytes), now)
                sourceStateRepo.upsert(SOURCE_ID, STATE_KEY_FORECAST_TIME, parsed.forecastTime.toString().encodeToByteArray(), now)
                sourceStateRepo.upsert(SOURCE_ID, STATE_KEY_OBSERVATION_TIME, parsed.observationTime.toString().encodeToByteArray(), now)
                OvationGrid(parsed.observationTime, parsed.forecastTime, parsed.probBytes)
            } catch (e: CancellationException) {
                // Cancellation is not a fetch failure — it must reach the caller,
                // which `runCatching` here would have swallowed.
                throw e
            } catch (e: Exception) {
                null
            } finally {
                if (httpClient == null) client.close()
            }
        }

        internal const val SOURCE_ID = "swpc"
    }
}
