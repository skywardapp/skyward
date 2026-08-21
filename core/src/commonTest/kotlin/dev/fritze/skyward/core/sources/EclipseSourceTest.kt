package dev.fritze.skyward.core.sources

import dev.fritze.skyward.core.model.LunarEclipsePayload
import dev.fritze.skyward.core.model.OccurrencePayload
import dev.fritze.skyward.core.model.SolarEclipseKind
import dev.fritze.skyward.core.model.SolarEclipsePayload
import dev.fritze.skyward.core.model.TimeWindow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.time.measureTimedValue

/**
 * §17.1/§17.2/§18 (M1 accept: "path sampling within runtime budget").
 * 2026-08-12 total solar eclipse figures (greatest-eclipse point ~65.5N
 * 25.42W, 45km off Iceland's west coast; max totality 2m18.2s) are from
 * Wikipedia's "Solar eclipse of August 12, 2026" article (fetched live
 * 2026-08-13), which cites Espenak/NASA GSFC figures — not this app's own
 * computation reflected back at itself.
 */
class EclipseSourceTest {

    private val source = EclipseSource()

    private suspend fun refresh(start: Instant, end: Instant, state: Map<String, ByteArray> = emptyMap()) = source.refresh(
        RefreshRequest(
            now = start,
            horizon = TimeWindow(start, end),
            locations = emptyList(),
            state = state,
            settings = SourceSettings(),
            derivedThresholds = DerivedThresholds(null, null, null),
        )
    )

    private fun RefreshResult.centralPathOf(occurrenceId: String) =
        (occurrences.first { it.id == occurrenceId }.payload as SolarEclipsePayload).centralPath

    @Test
    fun august2026TotalEclipseGreatestPointMatchesPublishedCoordinates() = runTest(timeout = 180.seconds) {
        val result = refresh(Instant.parse("2026-08-01T00:00:00Z"), Instant.parse("2026-08-20T00:00:00Z"))
        val eclipse = result.occurrences.first { it.id == "se:20260812" }
        val payload = eclipse.payload as SolarEclipsePayload

        assertEquals(SolarEclipseKind.TOTAL, payload.kind)
        // ~45km tolerance in each axis is generous for "45km off the coast"
        // reporting precision; this is a sanity/correctness check on the
        // global search, not a high-precision geodesy test.
        assertTrue(kotlin.math.abs(payload.greatestEclipsePoint.latDeg - 65.5) < 1.0)
        assertTrue(kotlin.math.abs(payload.greatestEclipsePoint.lonDeg - (-25.42)) < 1.0)
    }

    @Test
    fun august2026PathSamplingFindsCloseToThePublishedMaxDuration() = runTest(timeout = 180.seconds) {
        val (result, elapsed) = measureTimedValue {
            refresh(Instant.parse("2026-08-01T00:00:00Z"), Instant.parse("2026-08-20T00:00:00Z"))
        }
        // §18 M1 accept criterion: "path sampling within runtime budget"
        // (§7.1.3's own target is <60s desktop / <3min Android; this test
        // runs on the desktop JVM target).
        assertTrue(elapsed.inWholeSeconds < 60, "path sampling took $elapsed, expected <60s")

        val eclipse = result.occurrences.first { it.id == "se:20260812" }
        val payload = eclipse.payload as SolarEclipsePayload
        assertTrue(payload.centralPath.isNotEmpty(), "expected a non-empty central path for a total eclipse")

        val maxDurationSec = payload.centralPath.mapNotNull { it.centralDurationSec }.max()
        // Published max totality: 2m18.2s = 138.2s. The coarse grid + bounded
        // hill-climb (§7.1.3) is an approximation of the true maximum, not an
        // exact solve — allow a reasonable margin either side.
        assertTrue(maxDurationSec in 110.0..150.0, "expected max central duration near 138.2s, got $maxDurationSec")

        // Path should trace west-to-east-ish through the published region
        // (Siberia -> Arctic -> Greenland -> Iceland -> Spain) — every sample
        // must itself be a real total/annular point (self-consistency, §17.2).
        for (sample in payload.centralPath) {
            assertTrue(sample.centralDurationSec != null && sample.centralDurationSec > 0.0)
        }
    }

    /**
     * §7.1.3's budget — <60 s desktop, <3 min Android — is *per eclipse*, and
     * it says the sampling is "run once per eclipse, cached". Recomputing it
     * on every refresh instead is what let a 15-minute periodic pass run for
     * tens of minutes and take the polled sources down with it (issue #49).
     *
     * Also covers the pruning half: the runner upserts `newState` key by key
     * and never deletes, so a cache that did not drop what fell out of the
     * horizon would grow without bound.
     */
    @Test
    fun aSecondRefreshReusesTheCachedPathAndDropsEclipsesOutsideTheHorizon() = runTest(timeout = 180.seconds) {
        val start = Instant.parse("2026-08-01T00:00:00Z")
        val end = Instant.parse("2026-08-20T00:00:00Z")
        val first = refresh(start, end)
        val sampled = first.centralPathOf("se:20260812")
        assertTrue(sampled.isNotEmpty(), "expected the first refresh to sample a path")

        val (second, elapsed) = measureTimedValue { refresh(start, end, state = first.newState) }
        assertEquals(sampled, second.centralPathOf("se:20260812"), "a cached path must come back unchanged")
        // The coarse scan alone is thousands of local-eclipse searches; a
        // second refresh that took anywhere near that long did not use the
        // cache, whatever it returned.
        assertTrue(elapsed.inWholeSeconds < 5, "cached refresh took $elapsed, expected it to skip sampling entirely")

        // A window three years later holds no total or annular eclipse, so
        // nothing carries 2026's entry forward.
        val elsewhere = refresh(Instant.parse("2029-01-01T00:00:00Z"), Instant.parse("2029-02-01T00:00:00Z"), state = second.newState)
        val carried = elsewhere.newState.values.single().decodeToString()
        assertFalse("se:20260812" in carried, "a path outside the horizon must be dropped, not carried forever")
    }

    /**
     * The cache is an optimization; it may never be the reason a refresh
     * fails. A blob left by an older sampling tuning, or a truncated one,
     * costs a recompute and nothing else.
     */
    @Test
    fun anUnusableCachedPathBlobFallsBackToSampling() = runTest(timeout = 180.seconds) {
        val start = Instant.parse("2026-08-01T00:00:00Z")
        val end = Instant.parse("2026-08-20T00:00:00Z")
        val garbage = mapOf("central_paths_json" to """{"algorithm":"from-a-future-tuning","paths":{"se:20260812":[]}}""".encodeToByteArray())

        val result = refresh(start, end, state = garbage)

        assertTrue(result.centralPathOf("se:20260812").isNotEmpty(), "a stale fingerprint must trigger a resample, not an empty path")
        assertEquals(result.centralPathOf("se:20260812"), refresh(start, end, state = result.newState).centralPathOf("se:20260812"))
    }

    /**
     * A PARTIAL global eclipse has no shadow-axis intersection with the
     * Earth, so Astronomy Engine reports its latitude, longitude and
     * obscuration as NaN. Persisting one then fails outright — JSON has no
     * NaN — which took down the desktop app's first refresh with
     * `JsonEncodingException: Unexpected special floating-point value NaN`
     * from inside `GeoPoint`'s serializer. Every emitted occurrence must
     * carry finite, plottable coordinates.
     */
    @Test
    fun partialEclipsesCarryFiniteCoordinatesAndSurviveSerialization() = runTest(timeout = 180.seconds) {
        val result = refresh(Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2029-06-01T00:00:00Z"))
        val solar = result.occurrences.filter { it.id.startsWith("se:") }
        val partials = solar.filter { (it.payload as SolarEclipsePayload).kind == SolarEclipseKind.PARTIAL }
        assertTrue(partials.isNotEmpty(), "expected at least one partial solar eclipse in this span")

        for (occurrence in solar) {
            val payload = occurrence.payload as SolarEclipsePayload
            val point = payload.greatestEclipsePoint
            assertTrue(point.latDeg.isFinite() && point.lonDeg.isFinite(), "${occurrence.id}: non-finite greatest-eclipse point $point")
            assertTrue(point.latDeg in -90.0..90.0, "${occurrence.id}: latitude out of range ${point.latDeg}")
            assertTrue(point.lonDeg in -180.0..180.0, "${occurrence.id}: longitude out of range ${point.lonDeg}")
            assertTrue(
                payload.obscurationAtGreatest.isFinite() && payload.obscurationAtGreatest in 0.0..1.0,
                "${occurrence.id}: obscuration ${payload.obscurationAtGreatest} is not a fraction",
            )
            // The real failure was at the persistence boundary (§11), so assert there.
            val json = Json.encodeToString(OccurrencePayload.serializer(), payload)
            assertEquals(payload, Json.decodeFromString(OccurrencePayload.serializer(), json))
        }
    }

    /**
     * §7.1.3's per-bucket refinement is bounded on purpose. Central duration
     * increases monotonically along the path toward greatest eclipse, so an
     * unbounded climb walks every bucket off to the same global maximum and
     * the "path" degenerates into a handful of repeated coordinates with long
     * stretches of the real track missing. This asserts the shape a path must
     * have — distinct, time-ordered, spatially spread — rather than only that
     * its extreme value is right.
     */
    @Test
    fun august2026PathSamplesStayDistinctAndSpreadAlongTheTrack() = runTest(timeout = 180.seconds) {
        val result = refresh(Instant.parse("2026-08-01T00:00:00Z"), Instant.parse("2026-08-20T00:00:00Z"))
        val payload = result.occurrences.first { it.id == "se:20260812" }.payload as SolarEclipsePayload
        val path = payload.centralPath

        assertTrue(path.size >= 15, "expected a resolved centreline, got ${path.size} samples")
        assertEquals(path.size, path.map { it.time }.toSet().size, "path samples must have distinct times")
        assertEquals(path, path.sortedBy { it.time }, "path samples must be ordered in time")
        assertEquals(
            path.size,
            path.map { it.point.latDeg to it.point.lonDeg }.toSet().size,
            "path samples must be distinct points — repeated coordinates mean the refinement collapsed",
        )

        // A total eclipse's shadow crosses a wide swathe of the globe; a path
        // confined to a few degrees would mean most of it was never traced.
        val lonSpan = path.maxOf { it.point.lonDeg } - path.minOf { it.point.lonDeg }
        assertTrue(lonSpan > 40.0, "expected the path to span the globe's width, got $lonSpan degrees of longitude")
    }

    // Shares one refresh() across both checks below — each solar total/annular
    // eclipse in the horizon runs full path sampling (§7.1.3), so a 10-year
    // horizon queried twice was needlessly slow. A default 3-4 year horizon
    // (§7.1.2's own default) already contains a lunar eclipse and, per this
    // file's other test, at least one total solar eclipse.
    @Test
    fun lunarEclipsesHaveConsistentPhaseOrderingAndAllIdsAreUnique() = runTest(timeout = 180.seconds) {
        val result = refresh(Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2029-06-01T00:00:00Z"))

        val ids = result.occurrences.map { it.id }
        assertEquals(ids.toSet().size, ids.size, "expected all eclipse ids to be unique, got $ids")

        val lunarEclipses = result.occurrences.filter { it.id.startsWith("le:") }
        assertTrue(lunarEclipses.isNotEmpty(), "expected at least one lunar eclipse in this span")

        for (occ in lunarEclipses) {
            val payload = occ.payload as LunarEclipsePayload
            assertTrue(payload.penumbralBegin < payload.penumbralEnd)
            val partialBegin = payload.partialBegin
            val partialEnd = payload.partialEnd
            if (partialBegin != null && partialEnd != null) {
                assertTrue(payload.penumbralBegin <= partialBegin)
                assertTrue(partialBegin < partialEnd)
                assertTrue(partialEnd <= payload.penumbralEnd)
            }
            val totalBegin = payload.totalBegin
            val totalEnd = payload.totalEnd
            if (totalBegin != null && totalEnd != null) {
                assertTrue(partialBegin != null && partialEnd != null, "a total eclipse must also have a partial phase")
                assertTrue(partialBegin <= totalBegin)
                assertTrue(totalBegin < totalEnd)
                assertTrue(totalEnd <= partialEnd)
            }
        }
    }
}
