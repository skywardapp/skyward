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

    private suspend fun refresh(start: Instant, end: Instant) = source.refresh(
        RefreshRequest(
            now = start,
            horizon = TimeWindow(start, end),
            locations = emptyList(),
            state = emptyMap(),
            settings = SourceSettings(),
            derivedThresholds = DerivedThresholds(null, null, null),
        )
    )

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

        // Payload shape: a central-path sample without a positive duration is
        // a row that should never have been emitted. This is a check on what
        // the sampler *wrote*, not on where the point is — §17.2's
        // self-consistency requirement ("every PathSample.point must itself
        // evaluate as TOTAL via local search") needs an independent
        // re-evaluation, and lives in EclipsePathCanonTest, which also
        // compares the whole track against the GSFC canon.
        for (sample in payload.centralPath) {
            assertTrue(sample.centralDurationSec != null && sample.centralDurationSec > 0.0)
        }
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
