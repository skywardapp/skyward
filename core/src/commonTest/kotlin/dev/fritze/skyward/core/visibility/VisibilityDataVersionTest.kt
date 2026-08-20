package dev.fritze.skyward.core.visibility

import dev.fritze.skyward.core.model.AuroraForecastKind
import dev.fritze.skyward.core.model.AuroraPayload
import dev.fritze.skyward.core.model.Certainty
import dev.fritze.skyward.core.model.CometElements
import dev.fritze.skyward.core.model.CometMagParams
import dev.fritze.skyward.core.model.CometPayload
import dev.fritze.skyward.core.model.GeoPoint
import dev.fritze.skyward.core.model.Occurrence
import dev.fritze.skyward.core.model.Phenomenon
import dev.fritze.skyward.core.model.SolarEclipseKind
import dev.fritze.skyward.core.model.SolarEclipsePayload
import dev.fritze.skyward.core.model.TimeWindow
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * §11/§8.6: the `visibility_cache.data_version` key -- date-independent
 * models only track `occurrence.fetchedAt`, but the comet and aurora-NOWCAST
 * models must also invalidate on the pieces of state that move independently
 * of a re-fetch (§8.6's cache note; issue #18).
 */
class VisibilityDataVersionTest {

    private val now = Instant.parse("2026-06-15T12:00:00Z")
    private val utc = TimeZone.UTC

    private fun eclipseOcc(fetchedAt: Instant) = Occurrence(
        id = "se:1", phenomenon = Phenomenon.SOLAR_ECLIPSE, sourceId = "eclipse", title = "Eclipse",
        window = TimeWindow(now, now + 3.hours), peakTime = now + 1.hours, certainty = Certainty.CERTAIN,
        payload = SolarEclipsePayload(SolarEclipseKind.TOTAL, GeoPoint(0.0, 0.0), now + 1.hours, emptyList(), 1.0),
        fetchedAt = fetchedAt, expiresAt = null,
    )

    private val enckeElements = CometElements(
        epoch = Instant.parse("2023-10-22T03:35:18.402Z"),
        eccentricity = 0.8477496967533629,
        perihelionDistanceAu = 0.3379482792219925,
        inclinationDeg = 11.41227811179314,
        ascendingNodeDeg = 334.1935846036774,
        argPerihelionDeg = 187.1342463695676,
        tpPerihelion = Instant.parse("2023-10-22T03:35:18.402Z"),
    )

    private fun cometOcc(fetchedAt: Instant) = Occurrence(
        id = "cm:test", phenomenon = Phenomenon.COMET, sourceId = "jpl", title = "Comet",
        window = TimeWindow(now, now + 30.days), peakTime = now + 15.days, certainty = Certainty.FORECAST,
        payload = CometPayload(
            designation = "C/2025 K1", name = null, elements = enckeElements, magParams = CometMagParams(6.0, 10.0),
            perihelionDate = enckeElements.tpPerihelion, peakMag = 4.0, peakMagDate = now + 15.days, magAtIngest = 4.0,
        ),
        fetchedAt = fetchedAt, expiresAt = null,
    )

    private fun auroraOcc(kind: AuroraForecastKind, fetchedAt: Instant) = Occurrence(
        id = "au:1", phenomenon = Phenomenon.AURORA, sourceId = "swpc", title = "Aurora",
        window = TimeWindow(now, now + 3.hours), peakTime = null, certainty = Certainty.FORECAST,
        payload = AuroraPayload(kpForecast = 6.0, forecastKind = kind, issuedAt = fetchedAt),
        fetchedAt = fetchedAt, expiresAt = null,
    )

    private fun grid(observationTime: Instant) =
        OvationGrid(observationTime, observationTime + 30.minutes, ByteArray(360 * 181))

    @Test
    fun dateIndependentModelsVersionOnFetchedAtAlone() {
        val ctx = VisibilityContext(now = now, ovationGrid = null)
        val v1 = computeDataVersion(eclipseOcc(fetchedAt = now), ctx, utc)
        val v2 = computeDataVersion(eclipseOcc(fetchedAt = now), ctx, utc)
        val v3 = computeDataVersion(eclipseOcc(fetchedAt = now + 1.hours), ctx, utc)

        assertEquals(v1, v2, "same fetchedAt, same version")
        assertNotEquals(v1, v3, "a re-fetch (new fetchedAt) must change the version")
    }

    @Test
    fun cometVersionChangesAcrossLocalCalendarDates() {
        val occ = cometOcc(fetchedAt = now)
        val sameDay = VisibilityContext(now = now + 2.hours, ovationGrid = null)
        val nextDay = VisibilityContext(now = now + 1.days, ovationGrid = null)

        val v1 = computeDataVersion(occ, VisibilityContext(now = now, ovationGrid = null), utc)
        val v2 = computeDataVersion(occ, sameDay, utc)
        val v3 = computeDataVersion(occ, nextDay, utc)

        assertEquals(v1, v2, "same fetchedAt and calendar date -- unchanged")
        assertNotEquals(v1, v3, "§8.6: the night containing `now` moved to a new calendar date")
    }

    @Test
    fun threeDayAuroraIsDateIndependent() {
        val occ = auroraOcc(AuroraForecastKind.THREE_DAY, fetchedAt = now)
        val v1 = computeDataVersion(occ, VisibilityContext(now = now, ovationGrid = null), utc)
        val v2 = computeDataVersion(occ, VisibilityContext(now = now + 1.days, ovationGrid = null), utc)

        assertEquals(v1, v2, "THREE_DAY aurora never reads ctx.ovationGrid, so it doesn't need a date component")
    }

    @Test
    fun nowcastAuroraVersionTracksTheOvationGridObservationTime() {
        val occ = auroraOcc(AuroraForecastKind.NOWCAST, fetchedAt = now)
        val gridA = grid(now)
        val gridB = grid(now + 30.minutes)

        val vNoGrid = computeDataVersion(occ, VisibilityContext(now = now, ovationGrid = null), utc)
        val vGridA = computeDataVersion(occ, VisibilityContext(now = now, ovationGrid = gridA), utc)
        val vGridB = computeDataVersion(occ, VisibilityContext(now = now, ovationGrid = gridB), utc)

        assertNotEquals(vNoGrid, vGridA, "a grid becoming available must change the version")
        assertNotEquals(vGridA, vGridB, "a newer grid (independent of the occurrence's own fetchedAt) must change the version")
    }
}
