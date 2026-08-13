package dev.fritze.skyward.core.sources

import dev.fritze.skyward.core.model.LunarEclipsePayload
import dev.fritze.skyward.core.model.SolarEclipseKind
import dev.fritze.skyward.core.model.SolarEclipsePayload
import dev.fritze.skyward.core.model.TimeWindow
import kotlinx.coroutines.test.runTest
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

        // Path should trace west-to-east-ish through the published region
        // (Siberia -> Arctic -> Greenland -> Iceland -> Spain) — every sample
        // must itself be a real total/annular point (self-consistency, §17.2).
        for (sample in payload.centralPath) {
            assertTrue(sample.centralDurationSec != null && sample.centralDurationSec > 0.0)
        }
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
