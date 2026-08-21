package dev.fritze.skyward.core.sources

import dev.fritze.skyward.core.astro.toAstroTime
import dev.fritze.skyward.core.astro.toInstant
import dev.fritze.skyward.core.model.GeoPoint
import dev.fritze.skyward.core.model.LunarEclipseKind
import dev.fritze.skyward.core.model.SolarEclipseKind
import dev.fritze.skyward.core.testing.Fixtures
import io.github.cosinekitty.astronomy.EclipseKind
import io.github.cosinekitty.astronomy.GlobalSolarEclipseInfo
import io.github.cosinekitty.astronomy.Observer
import io.github.cosinekitty.astronomy.globalSolarEclipsesAfter
import io.github.cosinekitty.astronomy.lunarEclipsesAfter
import io.github.cosinekitty.astronomy.searchLocalSolarEclipse
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class EclipseCanonFixtureTest {

    @Test
    fun solarCanonSweep2020To2040MatchesKindPeakAndGreatestPoint() {
        val rows = Fixtures.csv("gsfc_solar_eclipses_2020_2040.csv").map {
            SolarCanonRow(
                date = it.getValue("date"),
                greatestTimeUtc = Instant.parse(it.getValue("greatest_time_utc")),
                expectedKind = SolarEclipseKind.valueOf(it.getValue("expected_kind")),
                latDeg = it.getValue("lat_deg").toDouble(),
                lonDeg = it.getValue("lon_deg").toDouble(),
            )
        }

        val start = Instant.parse("2020-01-01T00:00:00Z").toAstroTime()
        val end = Instant.parse("2041-01-01T00:00:00Z")
        val byDate = mutableMapOf<String, GlobalSolarEclipseInfo>()
        for (e in globalSolarEclipsesAfter(start)) {
            val peak = e.peak.toInstant()
            if (peak >= end) break
            byDate[peak.toString().substring(0, 10)] = e
        }

        assertEquals(rows.size, byDate.size, "expected one computed solar eclipse per fixture row")

        for (row in rows) {
            val eclipse = assertNotNull(byDate[row.date], "missing computed solar eclipse for ${row.date}")
            val computedKind = when (eclipse.kind) {
                EclipseKind.Partial -> SolarEclipseKind.PARTIAL
                EclipseKind.Annular -> SolarEclipseKind.ANNULAR
                EclipseKind.Total -> SolarEclipseKind.TOTAL
                EclipseKind.Penumbral -> error("global solar search never returns penumbral")
            }
            assertEquals(row.expectedKind, computedKind, "kind mismatch on ${row.date}")

            val deltaPeak = abs((eclipse.peak.toInstant() - row.greatestTimeUtc).inWholeSeconds)
            assertTrue(deltaPeak <= 2.minutes.inWholeSeconds, "${row.date}: peak delta=${deltaPeak}s")

            // GlobalSolarEclipseInfo.latitude/longitude are only defined for
            // Total/Annular eclipses (the shadow axis must actually intersect
            // the Earth); a partial-only eclipse has no engine-computed
            // greatest-eclipse point to check against the canon fixture.
            if (row.expectedKind != SolarEclipseKind.PARTIAL) {
                val point = GeoPoint(eclipse.latitude, eclipse.longitude)
                val distanceKm = haversineKm(point, GeoPoint(row.latDeg, row.lonDeg))
                assertTrue(distanceKm <= 30.0, "${row.date}: greatest-point delta=${"%.1f".format(distanceKm)} km")
            }
        }
    }

    @Test
    fun lunarCanonSweep2020To2040MatchesKindAndPeakTime() {
        val rows = Fixtures.csv("gsfc_lunar_eclipses_2020_2040.csv").map {
            LunarCanonRow(
                date = it.getValue("date"),
                peakUtc = Instant.parse(it.getValue("peak_time_utc")),
                expectedKind = LunarEclipseKind.valueOf(it.getValue("expected_kind")),
            )
        }

        val start = Instant.parse("2020-01-01T00:00:00Z").toAstroTime()
        val end = Instant.parse("2041-01-01T00:00:00Z")
        val byDate = mutableMapOf<String, io.github.cosinekitty.astronomy.LunarEclipseInfo>()
        for (e in lunarEclipsesAfter(start)) {
            val peak = e.peak.toInstant()
            if (peak >= end) break
            byDate[peak.toString().substring(0, 10)] = e
        }

        assertEquals(rows.size, byDate.size, "expected one computed lunar eclipse per fixture row")

        for (row in rows) {
            val eclipse = assertNotNull(byDate[row.date], "missing computed lunar eclipse for ${row.date}")
            val computedKind = when (eclipse.kind) {
                EclipseKind.Penumbral -> LunarEclipseKind.PENUMBRAL
                EclipseKind.Partial -> LunarEclipseKind.PARTIAL
                EclipseKind.Total -> LunarEclipseKind.TOTAL
                EclipseKind.Annular -> error("lunar search never returns annular")
            }
            assertEquals(row.expectedKind, computedKind, "kind mismatch on ${row.date}")

            val deltaPeak = abs((eclipse.peak.toInstant() - row.peakUtc).inWholeSeconds)
            assertTrue(deltaPeak <= 2.minutes.inWholeSeconds, "${row.date}: peak delta=${deltaPeak}s")
        }
    }

    @Test
    fun localCircumstancesNamedEclipseSpotChecksMatchPublishedTimes() {
        val rows = Fixtures.csv("gsfc_local_solar_circumstances_named_eclipses.csv").map {
            LocalCircumstanceRow(
                eclipseDate = it.getValue("eclipse_date"),
                city = it.getValue("city"),
                point = GeoPoint(it.getValue("latitude_deg").toDouble(), it.getValue("longitude_deg").toDouble()),
                expectedLocalKind = SolarEclipseKind.valueOf(it.getValue("expected_local_kind")),
                firstContactUtc = Instant.parse(it.getValue("first_contact_utc")),
                maxUtc = Instant.parse(it.getValue("max_utc")),
                lastContactUtc = Instant.parse(it.getValue("last_contact_utc")),
            )
        }

        val sydney2028 = rows.singleOrNull { it.eclipseDate == "2028-07-22" && it.city == "Sydney Australia" }
        assertNotNull(sydney2028, "fixture must include the 2028-07-22 Sydney totality spot-check")

        for (row in rows) {
            val searchStart = (row.maxUtc - 4.hours).toAstroTime()
            val local = searchLocalSolarEclipse(searchStart, Observer(row.point.latDeg, row.point.lonDeg, 0.0))

            val computedKind = when (local.kind) {
                EclipseKind.Total -> SolarEclipseKind.TOTAL
                EclipseKind.Annular -> SolarEclipseKind.ANNULAR
                EclipseKind.Partial -> SolarEclipseKind.PARTIAL
                EclipseKind.Penumbral -> error("local solar search never returns penumbral")
            }
            assertEquals(row.expectedLocalKind, computedKind, "${row.city} ${row.eclipseDate}: kind mismatch")

            assertWithinMinutes(local.partialBegin.time.toInstant(), row.firstContactUtc, row.city, row.eclipseDate, "first contact")
            assertWithinMinutes(local.peak.time.toInstant(), row.maxUtc, row.city, row.eclipseDate, "maximum")
            assertWithinMinutes(local.partialEnd.time.toInstant(), row.lastContactUtc, row.city, row.eclipseDate, "last contact")
        }
    }

    private fun assertWithinMinutes(
        actual: Instant,
        expected: Instant,
        city: String,
        eclipseDate: String,
        label: String,
    ) {
        val delta = abs((actual - expected).inWholeSeconds)
        assertTrue(
            delta <= 2.minutes.inWholeSeconds,
            "$city $eclipseDate $label delta=${delta}s (actual=$actual expected=$expected)",
        )
    }

    private fun haversineKm(a: GeoPoint, b: GeoPoint): Double {
        val r = 6371.0
        val dLat = (b.latDeg - a.latDeg).toRadians()
        val dLon = (b.lonDeg - a.lonDeg).toRadians()
        val lat1 = a.latDeg.toRadians()
        val lat2 = b.latDeg.toRadians()
        val h = sin(dLat / 2.0).pow(2.0) + cos(lat1) * cos(lat2) * sin(dLon / 2.0).pow(2.0)
        return 2.0 * r * asin(sqrt(h))
    }

    private fun Double.toRadians(): Double = this * PI / 180.0

    private data class SolarCanonRow(
        val date: String,
        val greatestTimeUtc: Instant,
        val expectedKind: SolarEclipseKind,
        val latDeg: Double,
        val lonDeg: Double,
    )

    private data class LunarCanonRow(
        val date: String,
        val peakUtc: Instant,
        val expectedKind: LunarEclipseKind,
    )

    private data class LocalCircumstanceRow(
        val eclipseDate: String,
        val city: String,
        val point: GeoPoint,
        val expectedLocalKind: SolarEclipseKind,
        val firstContactUtc: Instant,
        val maxUtc: Instant,
        val lastContactUtc: Instant,
    )
}
