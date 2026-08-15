package dev.fritze.skyward.ui.upcoming

import dev.fritze.skyward.core.model.AuroraForecastKind
import dev.fritze.skyward.core.model.AuroraPayload
import dev.fritze.skyward.core.model.Certainty
import dev.fritze.skyward.core.model.GeoPoint
import dev.fritze.skyward.core.model.Occurrence
import dev.fritze.skyward.core.model.Phenomenon
import dev.fritze.skyward.core.model.SavedLocation
import dev.fritze.skyward.core.model.TimeWindow
import dev.fritze.skyward.core.visibility.AuroraVisibilityModel
import dev.fritze.skyward.core.visibility.OvationGrid
import dev.fritze.skyward.core.visibility.VisibilityContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class UpcomingViewModelTest {

    private val now = Instant.parse("2026-01-01T18:00:00Z")
    private val primary = location("home", "Home", GeoPoint(60.0, 10.0), isPrimary = true)
    private val cabin = location("cabin", "Cabin", GeoPoint(65.0, 10.0), isPrimary = false)
    private val visibilityModels = mapOf(Phenomenon.AURORA to AuroraVisibilityModel())

    @Test
    fun activeAuroraBannerUsesPrimaryLocationAndLatestNowcast() {
        val older = nowcast("old", issuedAt = now - 40.minutes, expiresAt = now + 30.minutes)
        val latest = nowcast("latest", issuedAt = now - 5.minutes, expiresAt = now + 90.minutes)
        val ctx = VisibilityContext(now, grid(primary.point to 62, cabin.point to 88))

        val banner = activeAuroraBanner(
            occurrences = listOf(older, latest),
            locations = listOf(cabin, primary),
            visibilityModels = visibilityModels,
            ctx = ctx,
            currentKp = 5.7,
        )

        assertNotNull(banner)
        assertEquals("latest", banner.occurrenceId)
        assertEquals("Home", banner.locationName)
        assertEquals(62, banner.ovationProbabilityPercent)
        assertEquals(5.7, banner.currentKp)
        assertEquals(now - 5.minutes, banner.issuedAt)
    }

    @Test
    fun activeAuroraBannerIgnoresExpiredAndThreeDayAuroraRows() {
        val expired = nowcast("expired", issuedAt = now - 30.minutes, expiresAt = now)
        val threeDay = occurrence(
            id = "3day",
            issuedAt = now - 10.minutes,
            expiresAt = now + 2.hours,
            forecastKind = AuroraForecastKind.THREE_DAY,
        )

        val banner = activeAuroraBanner(
            occurrences = listOf(expired, threeDay),
            locations = listOf(primary),
            visibilityModels = visibilityModels,
            ctx = VisibilityContext(now, grid(primary.point to 62)),
            currentKp = null,
        )

        assertNull(banner)
    }

    private fun location(id: String, name: String, point: GeoPoint, isPrimary: Boolean) = SavedLocation(
        id = id,
        name = name,
        point = point,
        isPrimary = isPrimary,
        createdAt = now,
        modifiedAt = now,
    )

    private fun nowcast(id: String, issuedAt: Instant, expiresAt: Instant) = occurrence(
        id = id,
        issuedAt = issuedAt,
        expiresAt = expiresAt,
        forecastKind = AuroraForecastKind.NOWCAST,
    )

    private fun occurrence(
        id: String,
        issuedAt: Instant,
        expiresAt: Instant,
        forecastKind: AuroraForecastKind,
    ) = Occurrence(
        id = id,
        phenomenon = Phenomenon.AURORA,
        sourceId = "swpc",
        title = "Aurora",
        window = TimeWindow(issuedAt, issuedAt + 1.hours),
        peakTime = issuedAt,
        certainty = Certainty.FORECAST,
        payload = AuroraPayload(
            kpForecast = 6.0,
            forecastKind = forecastKind,
            issuedAt = issuedAt,
        ),
        fetchedAt = issuedAt,
        expiresAt = expiresAt,
    )

    private fun grid(vararg cells: Pair<GeoPoint, Int>): OvationGrid {
        val bytes = ByteArray(360 * 181)
        for ((point, probability) in cells) {
            val lon = point.lonDeg.toInt().mod(360)
            val lat = point.latDeg.toInt() + 90
            bytes[(lon * 181) + lat] = probability.toByte()
        }
        return OvationGrid(now, now, bytes)
    }
}
