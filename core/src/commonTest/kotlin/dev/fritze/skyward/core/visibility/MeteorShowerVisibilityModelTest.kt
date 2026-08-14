package dev.fritze.skyward.core.visibility

import dev.fritze.skyward.core.model.GeoPoint
import dev.fritze.skyward.core.model.MeteorShowerPayload
import dev.fritze.skyward.core.model.Occurrence
import dev.fritze.skyward.core.model.Quality
import dev.fritze.skyward.core.model.SavedLocation
import dev.fritze.skyward.core.model.TimeWindow
import dev.fritze.skyward.core.sources.DerivedThresholds
import dev.fritze.skyward.core.sources.MeteorShowerSource
import dev.fritze.skyward.core.sources.RefreshRequest
import dev.fritze.skyward.core.sources.SourceSettings
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class MeteorShowerVisibilityModelTest {

    private val model = MeteorShowerVisibilityModel()
    private val source = MeteorShowerSource()
    private val ctx = VisibilityContext(now = Instant.parse("2026-01-01T00:00:00Z"), ovationGrid = null)

    private fun loc(point: GeoPoint) = SavedLocation(
        id = "loc", name = "Test", point = point, isPrimary = true,
        createdAt = ctx.now, modifiedAt = ctx.now,
    )

    private suspend fun perseids2026(): Occurrence = source.refresh(
        RefreshRequest(
            now = ctx.now,
            horizon = TimeWindow(Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-12-31T00:00:00Z")),
            locations = emptyList(),
            state = emptyMap(),
            settings = SourceSettings(),
            derivedThresholds = DerivedThresholds(null, null, null),
        ),
    ).occurrences.first { it.id == "ms:PER:2026" }

    @Test
    fun perseids2026FromFiftyNorthIsExcellent() = runTest {
        val occ = perseids2026()
        val payload = occ.payload as MeteorShowerPayload
        // §17.4's own oracle framing: PER 2026 near-new-moon conditions from
        // 50N should read EXCELLENT (ZHR>=60, radiant well up, dark moon).
        val result = model.evaluate(occ, loc(GeoPoint(50.0, 10.0)), ctx)

        assertTrue(result.visibleAtLocation)
        assertEquals(Quality.EXCELLENT, result.quality, "ZHR=${payload.zhr}, moonIllum=${payload.moonIlluminationAtPeak}, details=${result.localDetails}")
        assertNull(result.travelDistanceKm, "meteor showers never offer travel guidance")
        assertNull(result.nearestVisiblePoint)
    }

    @Test
    fun perseids2026FromFortyFiveSouthIsNoneOrMarginal() = runTest {
        val occ = perseids2026()
        // The Perseid radiant (Perseus) never climbs far above the horizon
        // for a mid-southern-latitude observer.
        val result = model.evaluate(occ, loc(GeoPoint(-45.0, 10.0)), ctx)

        assertTrue(
            result.quality == Quality.NONE || result.quality == Quality.MARGINAL,
            "expected NONE or MARGINAL from 45S, got ${result.quality}",
        )
        assertNull(result.travelDistanceKm)
    }

    @Test
    fun aNeverDarkRadiantUpNightGetsNoneWithNullBestViewingWindow() = runTest {
        // Svalbard in June: permanent daylight, so no astronomical night
        // exists around any shower's peak that time of year.
        val occ = perseids2026() // peaks in August, not June, but the model
        // only cares about the night bracketing the occurrence's own
        // peakTime, so construct a synthetic mid-summer polar occurrence
        // instead of relying on Perseids' real August date.
        val payload = occ.payload as MeteorShowerPayload
        val midsummerPeak = Instant.parse("2026-06-21T12:00:00Z")
        val synthetic = Occurrence(
            id = "ms:TEST:2026",
            phenomenon = occ.phenomenon,
            sourceId = occ.sourceId,
            title = occ.title,
            window = TimeWindow(midsummerPeak - kotlin.time.Duration.parse("P1D"), midsummerPeak + kotlin.time.Duration.parse("P1D")),
            peakTime = midsummerPeak,
            certainty = occ.certainty,
            payload = payload,
            fetchedAt = occ.fetchedAt,
            expiresAt = occ.expiresAt,
        )
        val result = model.evaluate(synthetic, loc(GeoPoint(78.2, 15.6)), ctx)

        assertEquals(Quality.NONE, result.quality)
        val details = result.localDetails as? dev.fritze.skyward.core.model.LocalDetails.MeteorLocal
        assertNotNull(details)
        assertNull(details.bestViewingStart)
        assertNull(details.bestViewingEnd)
    }
}
