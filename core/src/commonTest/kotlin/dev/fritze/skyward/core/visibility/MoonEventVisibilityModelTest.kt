package dev.fritze.skyward.core.visibility

import dev.fritze.skyward.core.model.GeoPoint
import dev.fritze.skyward.core.model.MoonEventPayload
import dev.fritze.skyward.core.model.Quality
import dev.fritze.skyward.core.model.SavedLocation
import dev.fritze.skyward.core.model.TimeWindow
import dev.fritze.skyward.core.sources.DerivedThresholds
import dev.fritze.skyward.core.sources.MoonEventSource
import dev.fritze.skyward.core.sources.RefreshRequest
import dev.fritze.skyward.core.sources.SourceSettings
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class MoonEventVisibilityModelTest {

    private val model = MoonEventVisibilityModel()
    private val source = MoonEventSource()

    private fun loc(point: GeoPoint) = SavedLocation(
        id = "loc", name = "Test", point = point, isPrimary = true,
        createdAt = Instant.parse("2026-01-01T00:00:00Z"), modifiedAt = Instant.parse("2026-01-01T00:00:00Z"),
    )

    @Test
    fun supermoonQualityMatchesThePerigeeDistanceRuleAndTravelIsAlwaysNull() = runTest {
        val result = source.refresh(
            RefreshRequest(
                now = Instant.parse("2023-01-01T00:00:00Z"),
                horizon = TimeWindow(Instant.parse("2023-01-01T00:00:00Z"), Instant.parse("2024-03-01T00:00:00Z")),
                locations = emptyList(),
                state = emptyMap(),
                settings = SourceSettings(),
                derivedThresholds = DerivedThresholds(null, null, null),
            ),
        )
        assertTrue(result.occurrences.isNotEmpty(), "expected at least one supermoon in this span")

        val ctx = VisibilityContext(now = Instant.parse("2023-01-01T00:00:00Z"), ovationGrid = null)
        for (occ in result.occurrences) {
            val payload = occ.payload as MoonEventPayload
            // Munich, at a longitude/latitude combination that virtually
            // always sees the Moon up for some part of any given night.
            val visResult = model.evaluate(occ, loc(GeoPoint(48.1351, 11.5820)), ctx)

            assertNull(visResult.travelDistanceKm)
            assertNull(visResult.nearestVisiblePoint)
            if (visResult.quality != Quality.NONE) {
                val expected = if (payload.perigeeDistanceKm < 357_000.0) Quality.EXCELLENT else Quality.GOOD
                assertEquals(expected, visResult.quality, "occ=${occ.id} perigee=${payload.perigeeDistanceKm}")
            }
        }
    }
}
