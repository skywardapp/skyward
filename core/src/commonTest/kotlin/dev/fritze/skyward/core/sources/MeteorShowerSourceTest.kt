package dev.fritze.skyward.core.sources

import dev.fritze.skyward.core.astro.instantForSolarLongitudeInYear
import dev.fritze.skyward.core.astro.toAstroTime
import dev.fritze.skyward.core.model.MeteorShowerPayload
import dev.fritze.skyward.core.model.TimeWindow
import io.github.cosinekitty.astronomy.sunPosition
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class MeteorShowerSourceTest {

    private val source = MeteorShowerSource()

    private suspend fun refresh(start: Instant, end: Instant, includeMinor: Boolean = false) = source.refresh(
        RefreshRequest(
            now = start,
            horizon = TimeWindow(start, end),
            locations = emptyList(),
            state = emptyMap(),
            settings = SourceSettings(params = if (includeMinor) mapOf("includeMinor" to "true") else emptyMap()),
            derivedThresholds = DerivedThresholds(null, null, null),
        )
    )

    @Test
    fun solarLongitudeRootFindingRoundTrips() {
        // Internal consistency: whatever instant instantForSolarLongitudeInYear
        // returns must itself evaluate back to (approximately) the target
        // solar longitude under the same engine that defines "correct" here.
        for (target in listOf(0.0, 90.0, 180.0, 270.0, 283.15, 359.9)) {
            val t = instantForSolarLongitudeInYear(target, 2026)
            val actual = sunPosition(t.toAstroTime()).elon
            val diff = ((actual - target + 540.0) % 360.0) - 180.0
            assertTrue(kotlin.math.abs(diff) < 0.0001, "target=$target got lambda=$actual at $t")
        }
    }

    @Test
    fun quadrantidsStartFallsInThePreviousDecember() = runTest {
        // The specific bug this app's own ShowerCatalog doc comment flags:
        // QUA's catalog start (lambda=275) numerically precedes its peak
        // (lambda=283.15) but is a solar-longitude value from *before* New
        // Year's — i.e. December of the year before the peak, not after.
        val result = refresh(Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-01-31T00:00:00Z"))
        val qua = result.occurrences.first { it.id.startsWith("ms:QUA:") }
        val payload = qua.payload as MeteorShowerPayload

        val peakYear = qua.peakTime!!.toLocalDateTime(TimeZone.UTC).year
        val startYear = payload.activityStart.toLocalDateTime(TimeZone.UTC).year
        assertTrue(startYear < peakYear, "expected QUA's activity start ($startYear) before its peak year ($peakYear)")
        assertEquals(12, payload.activityStart.toLocalDateTime(TimeZone.UTC).monthNumber, "expected a December start")
        assertEquals(1, qua.peakTime!!.toLocalDateTime(TimeZone.UTC).monthNumber, "expected a January peak")
    }

    @Test
    fun perseidsPeakFallsInMidAugust() = runTest {
        // A well-established fact independent of this app: the Perseids
        // reliably peak within a couple of days of August 12-13 every year.
        val result = refresh(Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-12-31T00:00:00Z"))
        val per = result.occurrences.first { it.id == "ms:PER:2026" }
        val peakDate = per.peakTime!!.toLocalDateTime(TimeZone.UTC)
        assertEquals(8, peakDate.monthNumber)
        assertTrue(peakDate.dayOfMonth in 10..15, "expected PER 2026 peak around Aug 12-13, got $peakDate")
    }

    @Test
    fun geminidsHaveAFixedNonVariableZhr() = runTest {
        val result = refresh(Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-12-31T00:00:00Z"))
        val gem = result.occurrences.first { it.id == "ms:GEM:2026" }
        val payload = gem.payload as MeteorShowerPayload
        assertEquals(120, payload.zhr)
        assertNull(payload.zhrNote)
        val peakDate = gem.peakTime!!.toLocalDateTime(TimeZone.UTC)
        assertEquals(12, peakDate.monthNumber)
        assertTrue(peakDate.dayOfMonth in 12..15, "expected GEM 2026 peak around Dec 13-14, got $peakDate")
    }

    @Test
    fun variableShowersReportNullZhrWithANote() = runTest {
        val result = refresh(Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-12-31T00:00:00Z"))
        val eta = result.occurrences.first { it.id == "ms:ETA:2026" }
        val payload = eta.payload as MeteorShowerPayload
        assertNull(payload.zhr)
        assertNotNull(payload.zhrNote)
        assertTrue(payload.zhrNote!!.startsWith("variable,"))
    }

    @Test
    fun onlyMajorsAndZhrAtLeastTenAreCuratedByDefault() = runTest {
        val curated = refresh(Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-12-31T00:00:00Z"))
        val curatedCodes = curated.occurrences.map { it.id.substringAfter("ms:").substringBefore(":") }.toSet()

        // AND (Andromedids) has generic zhr=3 and is not in the majors list —
        // must be excluded by default.
        assertTrue("AND" !in curatedCodes, "AND should not be curated by default")
        // ARI (Daytime Arietids) has generic zhr=30 (>=10) and isn't a major —
        // should still be included on the zhr>=10 rule.
        assertTrue("ARI" in curatedCodes, "ARI (zhr=30) should be curated on the zhr>=10 rule")
        // QUA is variable-zhr but a major — must be included despite no fixed zhr.
        assertTrue("QUA" in curatedCodes, "QUA should be curated as a major despite variable zhr")

        val allShowers = refresh(Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-12-31T00:00:00Z"), includeMinor = true)
        val allCodes = allShowers.occurrences.map { it.id.substringAfter("ms:").substringBefore(":") }.toSet()
        assertTrue("AND" in allCodes, "AND should appear when includeMinor=true")
        assertTrue(allCodes.size > curatedCodes.size)
    }

    @Test
    fun idsAreUniqueAcrossTwoYears() = runTest {
        val result = refresh(Instant.parse("2025-01-01T00:00:00Z"), Instant.parse("2026-12-31T00:00:00Z"), includeMinor = true)
        val ids = result.occurrences.map { it.id }
        assertEquals(ids.toSet().size, ids.size, "expected all meteor shower ids to be unique, got $ids")
    }
}
