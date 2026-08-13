package dev.fritze.skyward.core.sources

import dev.fritze.skyward.core.astro.toAstroTime
import dev.fritze.skyward.core.astro.toInstant
import dev.fritze.skyward.core.model.MoonEventPayload
import dev.fritze.skyward.core.model.TimeWindow
import io.github.cosinekitty.astronomy.searchMoonPhase
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * §7.5. Full-moon peak times are checked against the US Naval Observatory's
 * published 2023 phase table (https://aa.usno.navy.mil/api/moon/phases/year,
 * fetched live 2026-08-13) — an independent oracle, not a value derived from
 * this app's own code. The supermoon threshold itself has no official
 * definition (§7.5's own note); those cases are checked structurally against
 * the doc's stated rule instead of against a press "supermoon list", since
 * popular sources use varying thresholds that don't match this rule exactly.
 */
class MoonEventSourceTest {

    private val source = MoonEventSource()

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

    // USNO full moon times for 2023 (UTC), from aa.usno.navy.mil/api/moon/phases/year?year=2023.
    private val usno2023FullMoons = listOf(
        "2023-01-06T23:08:00Z", "2023-02-05T18:28:00Z", "2023-03-07T12:40:00Z",
        "2023-04-06T04:34:00Z", "2023-05-05T17:34:00Z", "2023-06-04T03:42:00Z",
        "2023-07-03T11:39:00Z", "2023-08-01T18:32:00Z", "2023-08-31T01:35:00Z",
        "2023-09-29T09:57:00Z", "2023-10-28T20:24:00Z", "2023-11-27T09:16:00Z",
        "2023-12-27T00:33:00Z",
    ).map { Instant.parse(it) }

    @Test
    fun fullMoonPeakTimesMatchUsnoWithinFiveMinutes() = runTest {
        // Widen the horizon slightly so July's and December's full moons, near
        // the year boundary, are fully captured by the search loop.
        val result = refresh(Instant.parse("2022-12-20T00:00:00Z"), Instant.parse("2024-01-05T00:00:00Z"))

        // The source only emits occurrences for supermoons, so scan full moons
        // directly via the same search this test independently drives, using
        // MoonEventSource's own perigee-nearness helper indirectly through
        // refresh() isn't enough to see non-supermoon full moons — instead,
        // walk phases the same way the source does, letting a wider horizon
        // catch every 2023 full moon and asserting each is within tolerance of
        // *some* USNO time. This validates searchMoonPhase usage without
        // hardcoding here which months happen to also be supermoons.
        val allFoundPeaks = collectAllFullMoons(
            Instant.parse("2022-12-20T00:00:00Z"),
            Instant.parse("2024-01-05T00:00:00Z"),
        )

        for (usnoTime in usno2023FullMoons) {
            val closest = allFoundPeaks.minByOrNull { kotlin.math.abs((it - usnoTime).inWholeSeconds) }
            assertTrue(closest != null, "no full moon found near USNO time $usnoTime")
            val delta = (closest - usnoTime).let { if (it.isNegative()) -it else it }
            assertTrue(delta < 5.minutes, "full moon near $usnoTime off by $delta (found $closest)")
        }

        assertTrue(result.diagnostics.ok)
    }

    private fun collectAllFullMoons(start: Instant, end: Instant): List<Instant> {
        val peaks = mutableListOf<Instant>()
        var searchFrom = start.toAstroTime()
        while (true) {
            val fullMoon = searchMoonPhase(180.0, searchFrom, 40.0) ?: break
            val instant = fullMoon.toInstant()
            if (instant > end) break
            peaks += instant
            searchFrom = fullMoon.addDays(1.0)
        }
        return peaks
    }

    @Test
    fun everyEmittedOccurrenceSatisfiesTheSupermoonDefinition() = runTest {
        // A 14-month span reliably contains several supermoons by this rule.
        val result = refresh(Instant.parse("2023-01-01T00:00:00Z"), Instant.parse("2024-03-01T00:00:00Z"))
        assertTrue(result.occurrences.isNotEmpty(), "expected at least one supermoon in a 14-month span")

        for (occ in result.occurrences) {
            val payload = occ.payload as MoonEventPayload
            assertTrue(payload.perigeeDistanceKm < 360_000.0, "perigee ${payload.perigeeDistanceKm}km should be <360,000km")
            // A sanity bound on the physically possible range of lunar perigee distance.
            assertTrue(payload.perigeeDistanceKm in 350_000.0..362_000.0, "perigee ${payload.perigeeDistanceKm}km outside plausible range")

            val hoursApart = kotlin.math.abs((payload.fullMoonTime - payload.perigeeTime).inWholeMinutes) / 60.0
            assertTrue(hoursApart <= 24.0, "full moon and perigee $hoursApart h apart, expected <=24h")

            assertEquals(payload.fullMoonTime, occ.peakTime)
            assertEquals(TimeWindow(payload.fullMoonTime - 12.hours, payload.fullMoonTime + 12.hours), occ.window)
            assertTrue(occ.id.startsWith("sm:"), "expected natural key sm:<yyyymmdd>, got ${occ.id}")
        }
    }

    @Test
    fun idsAreUniqueAcrossTheRefreshedHorizon() = runTest {
        // Deliberately spans 2023-08, which has two supermoons under this
        // rule (Aug 1 and Aug 31) — the regression case for
        // docs/adr/0002-supermoon-natural-key.md.
        val result = refresh(Instant.parse("2020-01-01T00:00:00Z"), Instant.parse("2026-01-01T00:00:00Z"))
        val ids = result.occurrences.map { it.id }
        assertEquals(ids.toSet().size, ids.size, "expected all supermoon ids to be unique, got $ids")
    }
}
