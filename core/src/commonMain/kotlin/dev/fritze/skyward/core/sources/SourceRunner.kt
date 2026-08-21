package dev.fritze.skyward.core.sources

import dev.fritze.skyward.core.model.Certainty
import dev.fritze.skyward.core.model.SavedLocation
import dev.fritze.skyward.core.model.TimeWindow
import dev.fritze.skyward.core.persistence.LocationRepo
import dev.fritze.skyward.core.persistence.OccurrenceRepo
import dev.fritze.skyward.core.persistence.RuleRepo
import dev.fritze.skyward.core.persistence.SettingsRepo
import dev.fritze.skyward.core.persistence.SourceStateRepo
import dev.fritze.skyward.core.persistence.VisibilityCacheRepo
import dev.fritze.skyward.core.persistence.persistenceJson
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * §6.2: orchestrates every registered [EventSource]. Owns none of the
 * planning logic itself — after a run whose occurrences changed materially
 * (§6.3), it invokes [onOccurrencesChanged] and leaves re-planning to the
 * caller (kept decoupled from `core/planner` on purpose: sources shouldn't
 * need to know planner exists).
 */
class SourceRunner(
    private val sources: List<EventSource>,
    private val occurrenceRepo: OccurrenceRepo,
    private val sourceStateRepo: SourceStateRepo,
    private val settingsRepo: SettingsRepo,
    private val ruleRepo: RuleRepo,
    private val locationRepo: LocationRepo,
    private val visibilityCacheRepo: VisibilityCacheRepo,
    private val onOccurrencesChanged: suspend (now: Instant) -> Unit,
) {
    // Guards against RefreshWorker's periodic pass and a caller-triggered
    // force-refresh (onboarding's finish(), pull-to-refresh, §13.2)
    // overlapping: both can force the same COMPUTED sources, and without
    // this two concurrent runs redo each other's work against the same
    // SQLite rows, which on a slow device turns a few seconds of local
    // computation into minutes of lock contention rather than any actual
    // correctness gain -- runDue is naturally idempotent, so serializing
    // it costs nothing but a short wait for whichever call loses the race.
    private val runMutex = Mutex()

    /**
     * Runs every enabled source whose `next_run_at` is due, plus every
     * source in [force] regardless of due-ness (pull-to-refresh, §13.2).
     *
     * A source that has never run has no `next_run_at` and is due
     * immediately, so the first periodic pass after an install populates
     * everything; [force] is for reacting to a change *now* (onboarding
     * finishing, a settings edit, pull-to-refresh), not for bootstrapping.
     * [Schedule.OnHorizonChange] sources re-run daily — see
     * docs/adr/0009-daily-recompute-of-computed-sources.md.
     */
    suspend fun runDue(now: Instant, force: Set<String> = emptySet()): Unit = runMutex.withLock {
        val horizon = TimeWindow(now, now + (settingsRepo.getHorizonYears() * 365).days)
        val locations = locationRepo.getAll()
        val thresholds = deriveThresholds(ruleRepo.getEnabled())

        var anyMaterialChange = false
        // §6.2 calls the registry's order irrelevant; running the POLLED
        // sources first is what keeps that true. A pass has a budget --
        // WorkManager stops a non-expedited worker at ~10 minutes -- and the
        // COMPUTED sources are the ones able to spend it: EclipseSource's
        // first, uncached run samples every central eclipse in the horizon
        // (§7.1.3), which is minutes of CPU on a mid-range phone however well
        // it caches afterwards. Polling is seconds of mostly-idle network I/O.
        // Ordering it first means a pass that does run out of budget loses the
        // local astronomy, which the next pass recomputes from the same
        // ephemeris, rather than the fetches that are the only way fresh
        // aurora, comet and EONET data ever arrives (issue #49).
        for (source in sources.sortedBy { it.kind == SourceKind.COMPUTED }) {
            val enabled = settingsRepo.isSourceEnabled(source.id)
            val forced = source.id in force
            if (!forced && (!enabled || !isDue(source.id, now))) continue
            if (forced && !enabled) continue // an explicit force still respects "disabled"

            if (runOne(source, now, horizon, locations, thresholds)) anyMaterialChange = true
        }

        if (anyMaterialChange) onOccurrencesChanged(now)
    }

    suspend fun getDiagnostics(sourceId: String): SourceDiagnostics? =
        sourceStateRepo.getValue(sourceId, RUNNER_KEY_DIAGNOSTICS)?.decodeToString()
            ?.let { persistenceJson.decodeFromString(SourceDiagnostics.serializer(), it) }

    /**
     * No `next_run_at` means this source has never completed a run: it is due
     * now. Reading the absent key as "not due" instead left every source on a
     * fresh install waiting for something to [force] it — for the POLLED ones
     * (§7.3-§7.5) nothing on the periodic path ever did.
     */
    private suspend fun isDue(sourceId: String, now: Instant): Boolean {
        val raw = sourceStateRepo.getValue(sourceId, RUNNER_KEY_NEXT_RUN_AT) ?: return true
        return Instant.parse(raw.decodeToString()) <= now
    }

    /** Returns whether this run's changes were material enough to warrant a re-plan (§6.3). */
    private suspend fun runOne(
        source: EventSource,
        now: Instant,
        horizon: TimeWindow,
        locations: List<SavedLocation>,
        thresholds: DerivedThresholds,
    ): Boolean {
        val settings = loadSettings(source.id)
        val ownState = sourceStateRepo.getBySource(source.id).filterKeys { !it.startsWith(RUNNER_KEY_PREFIX) }
        val request = RefreshRequest(now, horizon, locations, ownState, settings, thresholds)

        val result = try {
            source.refresh(request)
        } catch (e: Exception) {
            onFailure(source.id, now, e)
            return false
        }

        val materialChange = upsertOccurrences(source.id, horizon, result)

        val nextRunAt = result.nextRefreshHint ?: nextRunAtFor(source.schedule(settings), now)
        persistRunnerState(source.id, now, nextRunAt, backoffCount = 0, diagnostics = result.diagnostics)
        for ((key, value) in result.newState) sourceStateRepo.upsert(source.id, key, value, now)

        return materialChange
    }

    /** §6.3: upsert (preserving `first_seen_at`), then drop withdrawn FORECAST rows (CERTAIN rows only if now out of horizon). */
    private suspend fun upsertOccurrences(sourceId: String, horizon: TimeWindow, result: RefreshResult): Boolean {
        var anyMaterial = false
        val freshIds = mutableSetOf<String>()
        for (occ in result.occurrences) {
            val previous = occurrenceRepo.getById(occ.id)
            val firstSeenAt = occurrenceRepo.getFirstSeenAt(occ.id) ?: occ.fetchedAt
            // `visibility_cache.data_version` is keyed on `fetched_at` (§11), so
            // rewriting the row with a fresh timestamp discards every cached
            // verdict for it. A COMPUTED source returns the same deterministic
            // rows on every run, so doing that unconditionally meant the cache
            // never once hit for eclipses, showers, moon events or conjunctions
            // (issue #49). An occurrence that came back byte-identical was not
            // meaningfully re-fetched: leave the row, and its cache, alone.
            val unchanged = previous != null && previous == occ.copy(fetchedAt = previous.fetchedAt)
            if (!unchanged) occurrenceRepo.upsert(occ, firstSeenAt = firstSeenAt)
            freshIds += occ.id
            if (previous == null || isMaterialChange(previous, occ)) anyMaterial = true
        }

        for (id in occurrenceRepo.getIdsBySource(sourceId) - freshIds) {
            val existing = occurrenceRepo.getById(id) ?: continue
            val outOfHorizon = existing.window.end < horizon.start || existing.window.start > horizon.end
            val shouldDelete = existing.certainty == Certainty.FORECAST || outOfHorizon
            if (shouldDelete) {
                occurrenceRepo.deleteById(id)
                visibilityCacheRepo.deleteByOccurrence(id) // §11: cached verdicts for a dropped occurrence are meaningless
                anyMaterial = true
            }
        }
        return anyMaterial
    }

    private suspend fun onFailure(sourceId: String, now: Instant, error: Exception) {
        val previousBackoff = sourceStateRepo.getValue(sourceId, RUNNER_KEY_BACKOFF_COUNT)?.decodeToString()?.toIntOrNull() ?: 0
        val backoffCount = (previousBackoff + 1).coerceAtMost(MAX_BACKOFF_STEPS)
        val delay = (BASE_BACKOFF * (1 shl backoffCount)).coerceAtMost(24.hours)
        val lastSuccessAt = getDiagnostics(sourceId)?.lastSuccessAt
        val diagnostics = SourceDiagnostics(ok = false, message = error.message ?: error::class.simpleName, lastSuccessAt = lastSuccessAt)
        persistRunnerState(sourceId, now, now + delay, backoffCount, diagnostics)
    }

    private suspend fun persistRunnerState(sourceId: String, now: Instant, nextRunAt: Instant, backoffCount: Int, diagnostics: SourceDiagnostics) {
        sourceStateRepo.upsert(sourceId, RUNNER_KEY_NEXT_RUN_AT, nextRunAt.toString().encodeToByteArray(), now)
        sourceStateRepo.upsert(sourceId, RUNNER_KEY_BACKOFF_COUNT, backoffCount.toString().encodeToByteArray(), now)
        val diagnosticsWithSuccess = if (diagnostics.ok) diagnostics.copy(lastSuccessAt = now) else diagnostics
        sourceStateRepo.upsert(
            sourceId,
            RUNNER_KEY_DIAGNOSTICS,
            persistenceJson.encodeToString(SourceDiagnostics.serializer(), diagnosticsWithSuccess).encodeToByteArray(),
            now,
        )
    }

    private suspend fun loadSettings(sourceId: String): SourceSettings {
        val stored = settingsRepo.get("source.$sourceId.settings_json")?.let { persistenceJson.decodeFromString<SourceSettings>(it) }
        return (stored ?: SourceSettings()).copy(enabled = settingsRepo.isSourceEnabled(sourceId))
    }

    /**
     * The horizon is `now .. now + horizonYears`, so it moves on its own: an
     * `OnHorizonChange` source has a genuinely different window to answer for
     * once a day, and none worth recomputing in between (its far edge is
     * years out; §7.1.2's default is 3). Recomputing them on every 15-minute
     * pass instead was what starved the POLLED sources behind them —
     * docs/adr/0009-daily-recompute-of-computed-sources.md.
     */
    private fun nextRunAtFor(schedule: Schedule, now: Instant): Instant = when (schedule) {
        is Schedule.OnHorizonChange -> now + HORIZON_RECHECK_INTERVAL
        is Schedule.Periodic -> now + schedule.interval
        is Schedule.Tiered -> now + schedule.idle
    }

    private companion object {
        const val RUNNER_KEY_PREFIX = "_runner."
        const val RUNNER_KEY_NEXT_RUN_AT = "_runner.next_run_at"
        const val RUNNER_KEY_BACKOFF_COUNT = "_runner.backoff_count"
        const val RUNNER_KEY_DIAGNOSTICS = "_runner.diagnostics"
        val HORIZON_RECHECK_INTERVAL = 1.days
        val BASE_BACKOFF = 15.minutes
        const val MAX_BACKOFF_STEPS = 7 // 15min * 2^7 = 32h, already past the 24h cap
    }
}
