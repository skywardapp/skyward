package dev.fritze.skyward.core.sources

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.fritze.skyward.core.model.Certainty
import dev.fritze.skyward.core.model.GeoPoint
import dev.fritze.skyward.core.model.Occurrence
import dev.fritze.skyward.core.model.Phenomenon
import dev.fritze.skyward.core.model.Quality
import dev.fritze.skyward.core.model.SolarEclipseKind
import dev.fritze.skyward.core.model.SolarEclipsePayload
import dev.fritze.skyward.core.model.TimeWindow
import dev.fritze.skyward.core.model.VisibilityResult
import dev.fritze.skyward.core.persistence.LocationRepo
import dev.fritze.skyward.core.persistence.OccurrenceRepo
import dev.fritze.skyward.core.persistence.RuleRepo
import dev.fritze.skyward.core.persistence.SettingsRepo
import dev.fritze.skyward.core.persistence.SkywardDatabase
import dev.fritze.skyward.core.persistence.SourceStateRepo
import dev.fritze.skyward.core.persistence.VisibilityCacheRepo
import dev.fritze.skyward.core.visibility.VisibilityCacheEntry
import dev.fritze.skyward.core.visibility.VisibilityCacheKey
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** §6.2/§6.3: orchestration, upsert-preserving-first-seen, withdrawal, backoff, and the material-change gate. */
class SourceRunnerTest {

    private val now = Instant.parse("2026-01-01T00:00:00Z")

    private class FakeSource(
        override val id: String,
        override val phenomena: Set<Phenomenon> = setOf(Phenomenon.SOLAR_ECLIPSE),
        private val schedule: Schedule = Schedule.OnHorizonChange,
        override val kind: SourceKind = SourceKind.COMPUTED,
        private val onRefresh: (String) -> Unit = {},
    ) : EventSource {
        var nextResult: RefreshResult? = null
        var nextError: Exception? = null
        var callCount = 0

        override suspend fun refresh(req: RefreshRequest): RefreshResult {
            callCount++
            onRefresh(id)
            nextError?.let { throw it }
            return nextResult ?: RefreshResult(emptyList(), emptyMap(), null, SourceDiagnostics(ok = true))
        }

        override fun schedule(settings: SourceSettings) = schedule
    }

    private fun occ(id: String, peakTime: Instant, certainty: Certainty, title: String = "t", fetchedAt: Instant = now) = Occurrence(
        id = id, phenomenon = Phenomenon.SOLAR_ECLIPSE, sourceId = "test-source", title = title,
        window = TimeWindow(peakTime - 1.hours, peakTime + 1.hours), peakTime = peakTime, certainty = certainty,
        payload = SolarEclipsePayload(SolarEclipseKind.TOTAL, GeoPoint(0.0, 0.0), peakTime, emptyList(), 1.0),
        fetchedAt = fetchedAt, expiresAt = null,
    )

    private class Fixture {
        val db: SkywardDatabase
        val occurrenceRepo: OccurrenceRepo
        val sourceStateRepo: SourceStateRepo
        val settingsRepo: SettingsRepo
        val ruleRepo: RuleRepo
        val locationRepo: LocationRepo
        val visibilityCacheRepo: VisibilityCacheRepo
        var replanCalls = 0
        var lastReplanNow: Instant? = null

        init {
            val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            SkywardDatabase.Schema.create(driver)
            db = SkywardDatabase(driver)
            occurrenceRepo = OccurrenceRepo(db)
            sourceStateRepo = SourceStateRepo(db)
            settingsRepo = SettingsRepo(db)
            ruleRepo = RuleRepo(db)
            locationRepo = LocationRepo(db)
            visibilityCacheRepo = VisibilityCacheRepo(db)
        }

        fun runner(vararg sources: EventSource) = SourceRunner(
            sources.toList(), occurrenceRepo, sourceStateRepo, settingsRepo, ruleRepo, locationRepo, visibilityCacheRepo,
            onOccurrencesChanged = { n -> replanCalls++; lastReplanNow = n },
        )
    }

    @Test
    fun forcedRunUpsertsOccurrencesStampsFirstSeenAtAndTriggersReplan() = runTest {
        val fx = Fixture()
        val source = FakeSource("test-source")
        source.nextResult = RefreshResult(listOf(occ("se:1", now + 1.days, Certainty.CERTAIN)), emptyMap(), null, SourceDiagnostics(ok = true))

        fx.runner(source).runDue(now, force = setOf("test-source"))

        assertEquals(1, source.callCount)
        assertEquals(1, fx.replanCalls, "a newly-seen occurrence is always material")
        val stored = fx.occurrenceRepo.getById("se:1")
        assertNotNull(stored)
        assertEquals(now, fx.occurrenceRepo.getFirstSeenAt("se:1"))
    }

    @Test
    fun secondFetchPreservesTheOriginalFirstSeenAt() = runTest {
        val fx = Fixture()
        val source = FakeSource("test-source")
        source.nextResult = RefreshResult(listOf(occ("se:1", now + 1.days, Certainty.CERTAIN)), emptyMap(), null, SourceDiagnostics(ok = true))
        fx.runner(source).runDue(now, force = setOf("test-source"))

        val later = now + 30.days
        source.nextResult = RefreshResult(listOf(occ("se:1", now + 1.days, Certainty.CERTAIN)), emptyMap(), null, SourceDiagnostics(ok = true))
        fx.runner(source).runDue(later, force = setOf("test-source"))

        assertEquals(now, fx.occurrenceRepo.getFirstSeenAt("se:1"), "first_seen_at must survive re-fetches")
    }

    /**
     * ADR 0009: the periodic drivers force nothing, so a source that has never
     * run has to become due by itself or a fresh install never populates.
     */
    @Test
    fun aSourceThatHasNeverRunIsDueOnTheFirstUnforcedPass() = runTest {
        val fx = Fixture()
        val computed = FakeSource("computed", schedule = Schedule.OnHorizonChange)
        val polled = FakeSource("polled", schedule = Schedule.Periodic(6.hours))

        fx.runner(computed, polled).runDue(now) // no force at all

        assertEquals(1, computed.callCount)
        assertEquals(1, polled.callCount)
    }

    /**
     * A pass is not unbounded — WorkManager stops a non-expedited worker at
     * ~10 minutes — and `EclipseSource`'s first, uncached run is the one thing
     * able to spend that whole budget (§7.1.3). Polling has to go first, or a
     * fresh install starves exactly the sources issue #49 was about, cache or
     * no cache. Registration order deliberately says otherwise here: §6.2
     * calls it irrelevant, and the runner is what has to make that true.
     */
    @Test
    fun aBootstrapPassRunsThePolledSourcesBeforeTheComputedOnes() = runTest {
        val fx = Fixture()
        val order = mutableListOf<String>()
        val computed = FakeSource("computed", kind = SourceKind.COMPUTED, onRefresh = { order += it })
        val polled = FakeSource(
            "polled",
            schedule = Schedule.Periodic(6.hours),
            kind = SourceKind.POLLED,
            onRefresh = { order += it },
        )

        fx.runner(computed, polled).runDue(now) // neither has ever run, so both are due

        assertEquals(listOf("polled", "computed"), order)
    }

    /**
     * ADR 0009: the horizon is `now .. now + horizonYears`, so its far edge
     * moves a day per day and an `OnHorizonChange` source has something new to
     * say once a day — and nothing in between, which is what stopped the
     * 15-minute recompute that starved the POLLED sources behind it (issue #49).
     */
    @Test
    fun onHorizonChangeSourceBecomesDueOnceADayAndNotSooner() = runTest {
        val fx = Fixture()
        val source = FakeSource("test-source", schedule = Schedule.OnHorizonChange)
        fx.runner(source).runDue(now, force = setOf("test-source"))
        assertEquals(1, source.callCount)

        fx.runner(source).runDue(now + 15.minutes) // the periodic driver's own cadence
        assertEquals(1, source.callCount, "a horizon that moved 15 minutes is not worth recomputing")

        fx.runner(source).runDue(now + 1.days)
        assertEquals(2, source.callCount, "a horizon that moved a day is")
    }

    @Test
    fun periodicSourceBecomesDueAfterItsInterval() = runTest {
        val fx = Fixture()
        val source = FakeSource("test-source", schedule = Schedule.Periodic(6.hours))
        fx.runner(source).runDue(now, force = setOf("test-source"))
        assertEquals(1, source.callCount)

        fx.runner(source).runDue(now + 3.hours) // not due yet
        assertEquals(1, source.callCount)

        fx.runner(source).runDue(now + 6.hours) // due
        assertEquals(2, source.callCount)
    }

    @Test
    fun nextRefreshHintOverridesTheScheduleDefault() = runTest {
        val fx = Fixture()
        val source = FakeSource("test-source", schedule = Schedule.Periodic(6.hours))
        source.nextResult = RefreshResult(emptyList(), emptyMap(), nextRefreshHint = now + 20.minutes, diagnostics = SourceDiagnostics(ok = true))
        fx.runner(source).runDue(now, force = setOf("test-source"))

        fx.runner(source).runDue(now + 20.minutes)
        assertEquals(2, source.callCount, "the hint (20min), not the schedule's own 6h, governs the next run")
    }

    @Test
    fun disabledSourceNeverRunsEvenWhenForced() = runTest {
        val fx = Fixture()
        fx.settingsRepo.setSourceEnabled("test-source", false)
        val source = FakeSource("test-source")

        fx.runner(source).runDue(now, force = setOf("test-source"))

        assertEquals(0, source.callCount)
        assertEquals(0, fx.replanCalls)
    }

    private suspend fun Fixture.seedVisibilityCacheEntry(occurrenceId: String, locationId: String = "home") {
        visibilityCacheRepo.upsertAll(
            mapOf(
                VisibilityCacheKey(occurrenceId, locationId) to
                    VisibilityCacheEntry("v1", VisibilityResult(true, Quality.GOOD, null, null, null, null, null), now),
            ),
        )
    }

    /** Seeds one FORECAST `se:1` via a forced run, then withdraws it via a second forced run with an empty result. */
    private suspend fun Fixture.seedThenWithdrawForecastOccurrence(source: FakeSource) {
        source.nextResult = RefreshResult(listOf(occ("se:1", now + 1.days, Certainty.FORECAST)), emptyMap(), null, SourceDiagnostics(ok = true))
        runner(source).runDue(now, force = setOf("test-source"))
        assertNotNull(occurrenceRepo.getById("se:1"))

        source.nextResult = RefreshResult(emptyList(), emptyMap(), null, SourceDiagnostics(ok = true))
        runner(source).runDue(now + 1.hours, force = setOf("test-source"))
    }

    @Test
    fun withdrawnForecastOccurrenceIsDeletedAndTriggersReplan() = runTest {
        val fx = Fixture()
        fx.seedThenWithdrawForecastOccurrence(FakeSource("test-source"))

        assertNull(fx.occurrenceRepo.getById("se:1"), "a withdrawn FORECAST occurrence must be deleted (§6.3)")
        assertEquals(2, fx.replanCalls)
    }

    @Test
    fun withdrawingAnOccurrenceInvalidatesItsVisibilityCacheEntries() = runTest {
        val fx = Fixture()
        val source = FakeSource("test-source")
        source.nextResult = RefreshResult(listOf(occ("se:1", now + 1.days, Certainty.FORECAST)), emptyMap(), null, SourceDiagnostics(ok = true))
        fx.runner(source).runDue(now, force = setOf("test-source"))
        // Two locations for the withdrawn occurrence, plus an unrelated one --
        // a delete-all implementation would also pass an assertTrue(isEmpty()).
        fx.seedVisibilityCacheEntry("se:1", "home")
        fx.seedVisibilityCacheEntry("se:1", "work")
        fx.seedVisibilityCacheEntry("se:other")
        assertTrue(fx.visibilityCacheRepo.getAll().containsKey(VisibilityCacheKey("se:1", "home")))

        source.nextResult = RefreshResult(emptyList(), emptyMap(), null, SourceDiagnostics(ok = true))
        fx.runner(source).runDue(now + 1.hours, force = setOf("test-source"))

        assertEquals(
            setOf(VisibilityCacheKey("se:other", "home")),
            fx.visibilityCacheRepo.getAll().keys,
            "only the withdrawn occurrence's cache entries must be dropped, not the unrelated one's (§11)",
        )
    }

    @Test
    fun withdrawnCertainOccurrenceIsKeptWhileStillWithinHorizon() = runTest {
        val fx = Fixture()
        val source = FakeSource("test-source")
        source.nextResult = RefreshResult(listOf(occ("se:1", now + 1.days, Certainty.CERTAIN)), emptyMap(), null, SourceDiagnostics(ok = true))
        fx.runner(source).runDue(now, force = setOf("test-source"))

        // Absent from the next result, but its window is still inside the horizon.
        source.nextResult = RefreshResult(emptyList(), emptyMap(), null, SourceDiagnostics(ok = true))
        fx.runner(source).runDue(now + 1.hours, force = setOf("test-source"))

        assertNotNull(fx.occurrenceRepo.getById("se:1"), "a transient source bug must not mass-delete CERTAIN occurrences")
    }

    @Test
    fun withdrawnCertainOccurrenceOutsideTheHorizonIsDeleted() = runTest {
        val fx = Fixture()
        val source = FakeSource("test-source")
        // A window entirely in the past relative to `now` -- outside [now, now+horizon].
        source.nextResult = RefreshResult(listOf(occ("se:1", now - 400.days, Certainty.CERTAIN)), emptyMap(), null, SourceDiagnostics(ok = true))
        fx.runner(source).runDue(now - 400.days, force = setOf("test-source"))
        assertNotNull(fx.occurrenceRepo.getById("se:1"))

        source.nextResult = RefreshResult(emptyList(), emptyMap(), null, SourceDiagnostics(ok = true))
        fx.runner(source).runDue(now, force = setOf("test-source"))

        assertNull(fx.occurrenceRepo.getById("se:1"), "a CERTAIN occurrence now outside the horizon may be pruned")
    }

    @Test
    fun horizonPruningAlsoInvalidatesTheVisibilityCache() = runTest {
        val fx = Fixture()
        val source = FakeSource("test-source")
        source.nextResult = RefreshResult(listOf(occ("se:1", now - 400.days, Certainty.CERTAIN)), emptyMap(), null, SourceDiagnostics(ok = true))
        fx.runner(source).runDue(now - 400.days, force = setOf("test-source"))
        fx.seedVisibilityCacheEntry("se:1", "home")
        fx.seedVisibilityCacheEntry("se:1", "work")
        fx.seedVisibilityCacheEntry("se:other")

        source.nextResult = RefreshResult(emptyList(), emptyMap(), null, SourceDiagnostics(ok = true))
        fx.runner(source).runDue(now, force = setOf("test-source"))

        assertEquals(
            setOf(VisibilityCacheKey("se:other", "home")),
            fx.visibilityCacheRepo.getAll().keys,
            "pruning a CERTAIN occurrence outside the horizon must drop only its own cache entries",
        )
    }

    @Test
    fun aPurelyCosmeticRefetchUpdatesTheRowButDoesNotTriggerReplan() = runTest {
        val fx = Fixture()
        val source = FakeSource("test-source")
        source.nextResult = RefreshResult(listOf(occ("se:1", now + 1.days, Certainty.CERTAIN, title = "Old title")), emptyMap(), null, SourceDiagnostics(ok = true))
        fx.runner(source).runDue(now, force = setOf("test-source"))
        assertEquals(1, fx.replanCalls)

        // Same peakTime/kind -- not on §6.3's materiality list -- but a different title.
        source.nextResult = RefreshResult(listOf(occ("se:1", now + 1.days, Certainty.CERTAIN, title = "New title")), emptyMap(), null, SourceDiagnostics(ok = true))
        fx.runner(source).runDue(now + 1.hours, force = setOf("test-source"))

        assertEquals(1, fx.replanCalls, "no material change -- the runner must not re-plan")
        assertEquals("New title", fx.occurrenceRepo.getById("se:1")?.title, "but the stored row is still refreshed")
    }

    /**
     * §11 keys `visibility_cache.data_version` on `fetched_at`, so a re-run
     * that rewrites it throws away every cached verdict for the occurrence.
     * COMPUTED sources return the same deterministic rows on every run, which
     * made that cache a permanent miss for computed phenomena (issue #49).
     */
    @Test
    fun aReRunThatReturnsTheSameOccurrenceLeavesItsFetchedAtAlone() = runTest {
        val fx = Fixture()
        val source = FakeSource("test-source")
        source.nextResult = RefreshResult(listOf(occ("se:1", now + 400.days, Certainty.CERTAIN)), emptyMap(), null, SourceDiagnostics(ok = true))
        fx.runner(source).runDue(now, force = setOf("test-source"))

        val later = now + 1.days
        source.nextResult = RefreshResult(
            listOf(occ("se:1", now + 400.days, Certainty.CERTAIN, fetchedAt = later)),
            emptyMap(), null, SourceDiagnostics(ok = true),
        )
        fx.runner(source).runDue(later, force = setOf("test-source"))

        assertEquals(now, fx.occurrenceRepo.getById("se:1")?.fetchedAt, "an unchanged occurrence was not meaningfully re-fetched")
    }

    @Test
    fun aReRunThatChangedTheOccurrenceDoesBumpFetchedAt() = runTest {
        val fx = Fixture()
        val source = FakeSource("test-source")
        source.nextResult = RefreshResult(
            listOf(occ("se:1", now + 400.days, Certainty.CERTAIN, title = "Old title")),
            emptyMap(), null, SourceDiagnostics(ok = true),
        )
        fx.runner(source).runDue(now, force = setOf("test-source"))

        val later = now + 1.days
        source.nextResult = RefreshResult(
            // Not material (§6.3) but genuinely different data -- the cached
            // verdicts were computed against the old row and must not be kept.
            listOf(occ("se:1", now + 400.days, Certainty.CERTAIN, title = "New title", fetchedAt = later)),
            emptyMap(), null, SourceDiagnostics(ok = true),
        )
        fx.runner(source).runDue(later, force = setOf("test-source"))

        val stored = fx.occurrenceRepo.getById("se:1")
        assertEquals("New title", stored?.title)
        assertEquals(later, stored?.fetchedAt, "a changed occurrence must invalidate its visibility-cache entries")
    }

    @Test
    fun aFailingSourceBacksOffAndDoesNotTriggerReplanOrBlockOthers() = runTest {
        val fx = Fixture()
        val failing = FakeSource("failing")
        failing.nextError = RuntimeException("boom")
        val healthy = FakeSource("healthy")
        healthy.nextResult = RefreshResult(listOf(occ("se:1", now + 1.days, Certainty.CERTAIN)), emptyMap(), null, SourceDiagnostics(ok = true))

        fx.runner(failing, healthy).runDue(now, force = setOf("failing", "healthy"))

        assertEquals(1, fx.replanCalls, "the healthy source's material change still triggers a replan")
        assertNotNull(fx.occurrenceRepo.getById("se:1"))
        val diagnostics = fx.runner(failing, healthy).getDiagnostics("failing")
        assertNotNull(diagnostics)
        assertFalse(diagnostics.ok)
    }

    @Test
    fun repeatedFailuresBackOffExponentiallyCappedAtOneDay() = runTest {
        val fx = Fixture()
        val source = FakeSource("test-source")
        source.nextError = RuntimeException("boom")
        val runner = fx.runner(source)

        var t = now
        val delays = mutableListOf<Duration>()
        repeat(6) {
            runner.runDue(t, force = setOf("test-source"))
            val raw = fx.sourceStateRepo.getValue("test-source", "_runner.next_run_at")
            assertNotNull(raw)
            val nextRunAt = Instant.parse(raw.decodeToString())
            delays += nextRunAt - t
            t = nextRunAt // advance exactly to the next due time each round
        }

        assertTrue(delays.zipWithNext().all { (a, b) -> b >= a }, "backoff must never shrink: $delays")
        assertTrue(delays.last() <= 24.hours, "backoff must be capped at 24h: ${delays.last()}")
    }

    /**
     * A source that *returns* `ok = false` instead of throwing (e.g.
     * AuroraSource's OVATION-failed-but-forecast-ok path) skips [onFailure]
     * entirely, so nothing but [persistRunnerState] itself stands between a
     * partial-failure run and clobbering the real previous success time.
     */
    @Test
    fun aReturnedNotOkRefreshPreservesThePreviousLastSuccessAt() = runTest {
        val fx = Fixture()
        val source = FakeSource("test-source")
        val runner = fx.runner(source)

        source.nextResult = RefreshResult(emptyList(), emptyMap(), null, SourceDiagnostics(ok = true))
        runner.runDue(now, force = setOf("test-source"))
        val afterSuccess = runner.getDiagnostics("test-source")
        assertEquals(now, afterSuccess?.lastSuccessAt)

        val later = now + 1.hours
        source.nextResult = RefreshResult(emptyList(), emptyMap(), null, SourceDiagnostics(ok = false, message = "OVATION fetch failed", lastSuccessAt = null))
        runner.runDue(later, force = setOf("test-source"))
        val afterPartialFailure = runner.getDiagnostics("test-source")

        assertNotNull(afterPartialFailure)
        assertFalse(afterPartialFailure.ok)
        assertEquals(now, afterPartialFailure.lastSuccessAt, "the earlier success timestamp must survive a returned-not-ok run")
    }
}
