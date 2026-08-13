package dev.fritze.skyward.core.visibility

import dev.fritze.skyward.core.model.Certainty
import dev.fritze.skyward.core.model.ConjunctionPayload
import dev.fritze.skyward.core.model.GeoPoint
import dev.fritze.skyward.core.model.Occurrence
import dev.fritze.skyward.core.model.Phenomenon
import dev.fritze.skyward.core.model.Quality
import dev.fritze.skyward.core.model.SavedLocation
import dev.fritze.skyward.core.model.TimeWindow
import dev.fritze.skyward.core.sources.ConjunctionSource
import dev.fritze.skyward.core.sources.DerivedThresholds
import dev.fritze.skyward.core.sources.RefreshRequest
import dev.fritze.skyward.core.sources.SourceSettings
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class ConjunctionVisibilityModelTest {

    private val model = ConjunctionVisibilityModel()
    private val source = ConjunctionSource()

    private fun loc(point: GeoPoint) = SavedLocation(
        id = "loc", name = "Test", point = point, isPrimary = true,
        createdAt = Instant.parse("2023-01-01T00:00:00Z"), modifiedAt = Instant.parse("2023-01-01T00:00:00Z"),
    )

    @Test
    fun documentedMarch2023VenusJupiterConjunctionIsEvaluableAndTravelIsAlwaysNull() = runTest {
        val result = source.refresh(
            RefreshRequest(
                now = Instant.parse("2023-02-20T00:00:00Z"),
                horizon = TimeWindow(Instant.parse("2023-02-20T00:00:00Z"), Instant.parse("2023-03-10T00:00:00Z")),
                locations = emptyList(),
                state = emptyMap(),
                settings = SourceSettings(),
                derivedThresholds = DerivedThresholds(null, null, null),
            ),
        )
        val occ = result.occurrences.first { occ ->
            val p = occ.payload as ConjunctionPayload
            setOf(p.body1, p.body2) == setOf("Venus", "Jupiter")
        }
        val payload = occ.payload as ConjunctionPayload
        val ctx = VisibilityContext(now = Instant.parse("2023-02-20T00:00:00Z"), ovationGrid = null)

        // Munich: a mid-northern-latitude location where a well-documented
        // evening apparition like this one is a real, checkable case.
        val visResult = model.evaluate(occ, loc(GeoPoint(48.1351, 11.5820)), ctx)

        assertNull(visResult.travelDistanceKm)
        assertNull(visResult.nearestVisiblePoint)
        // The documented March 2023 Venus-Jupiter conjunction is a real,
        // well-known evening apparition -- if this evaluates to NONE, the
        // gate logic (or the 15-min sampling window) is broken, not the fixture.
        assertTrue(visResult.quality != Quality.NONE, "expected the documented Venus-Jupiter conjunction to be visible from Munich")
        val expected = when {
            payload.minSeparationDeg < 0.5 -> Quality.EXCELLENT
            payload.minSeparationDeg < 1.0 -> Quality.GOOD
            else -> Quality.MARGINAL
        }
        assertEquals(expected, visResult.quality)
    }

    @Test
    fun anUnrecognizedBodyNameIsNoneRatherThanACrash() {
        val occ = Occurrence(
            id = "cj:test",
            phenomenon = Phenomenon.CONJUNCTION,
            sourceId = "test",
            title = "Conjunction",
            window = TimeWindow(Instant.parse("2023-01-01T00:00:00Z"), Instant.parse("2023-01-02T00:00:00Z")),
            peakTime = Instant.parse("2023-01-01T12:00:00Z"),
            certainty = Certainty.CERTAIN,
            // Simulates a stale/legacy persisted body name the vendored
            // `Body` enum no longer recognizes (§6.4: payloads persist
            // plain strings, not the enum, for exactly this reason).
            payload = ConjunctionPayload(
                body1 = "Venus",
                body2 = "NoLongerARealBody",
                minSeparationDeg = 0.3,
                timeOfClosest = Instant.parse("2023-01-01T12:00:00Z"),
            ),
            fetchedAt = Instant.parse("2023-01-01T00:00:00Z"),
            expiresAt = null,
        )
        val ctx = VisibilityContext(now = Instant.parse("2023-01-01T00:00:00Z"), ovationGrid = null)

        val result = model.evaluate(occ, loc(GeoPoint(48.1351, 11.5820)), ctx)

        assertEquals(Quality.NONE, result.quality)
        assertTrue(!result.visibleAtLocation)
    }

    @Test
    fun everyRealConjunctionInAFullYearEvaluatesConsistentlyWithItsOwnSeparation() = runTest {
        val result = source.refresh(
            RefreshRequest(
                now = Instant.parse("2024-01-01T00:00:00Z"),
                horizon = TimeWindow(Instant.parse("2024-01-01T00:00:00Z"), Instant.parse("2025-01-01T00:00:00Z")),
                locations = emptyList(),
                state = emptyMap(),
                settings = SourceSettings(),
                derivedThresholds = DerivedThresholds(null, null, null),
            ),
        )
        assertTrue(result.occurrences.isNotEmpty())
        val ctx = VisibilityContext(now = Instant.parse("2024-01-01T00:00:00Z"), ovationGrid = null)
        val here = loc(GeoPoint(48.1351, 11.5820))

        var sawVisible = false
        for (occ in result.occurrences) {
            val payload = occ.payload as ConjunctionPayload
            val visResult = model.evaluate(occ, here, ctx)
            if (visResult.quality != Quality.NONE) {
                sawVisible = true
                val expected = when {
                    payload.minSeparationDeg < 0.5 -> Quality.EXCELLENT
                    payload.minSeparationDeg < 1.0 -> Quality.GOOD
                    else -> Quality.MARGINAL
                }
                assertEquals(expected, visResult.quality, "occ=${occ.id} sep=${payload.minSeparationDeg}")
            }
        }
        // Otherwise a model that always returns NONE would pass this test
        // vacuously -- a full year from Munich has real visible conjunctions.
        assertTrue(sawVisible, "expected at least one visible conjunction across a full year")
    }
}
