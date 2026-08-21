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
        val rows = localCircumstanceRows()

        // §17.1 asks for three cities per named eclipse; assert that rather
        // than only iterating whatever the fixture happens to hold, since a
        // silently shrinking fixture is how this requirement erodes.
        for (eclipseDate in listOf("2026-08-12", "2027-08-02", "2028-07-22")) {
            val cities = rows.filter { it.eclipseDate == eclipseDate }
            assertEquals(3, cities.size, "§17.1 wants 3 cities for $eclipseDate, fixture has ${cities.map { it.city }}")
        }
        assertTrue(
            rows.any { it.eclipseDate == "2028-07-22" && it.city == "Sydney Australia" && it.expectedLocalKind == SolarEclipseKind.TOTAL },
            "fixture must include the 2028-07-22 Sydney totality spot-check",
        )

        for (row in rows) {
            val local = localCircumstancesAt(row.point, row.maxUtc)

            val computedKind = when (local.kind) {
                EclipseKind.Total -> SolarEclipseKind.TOTAL
                EclipseKind.Annular -> SolarEclipseKind.ANNULAR
                EclipseKind.Partial -> SolarEclipseKind.PARTIAL
                EclipseKind.Penumbral -> error("local solar search never returns penumbral")
            }
            assertEquals(row.expectedLocalKind, computedKind, "${row.city} ${row.eclipseDate}: kind mismatch")

            assertWithinMinutes(local.partialBegin.time.toInstant(), row.firstContactUtc, row.city, row.eclipseDate, "first contact")
            assertWithinMinutes(local.peak.time.toInstant(), row.maxUtc, row.city, row.eclipseDate, "maximum")
            row.lastContactUtc?.let {
                assertWithinMinutes(local.partialEnd.time.toInstant(), it, row.city, row.eclipseDate, "last contact")
            }
            row.totalityDurationSec?.let { published ->
                val computed = totalityDurationSec(local)
                assertNotNull(computed, "${row.city} ${row.eclipseDate}: expected totality, got ${local.kind}")
                // §17.1 asks for published *values*, and a published duration
                // is quoted to the second for a city, not for the reader's
                // exact coordinates — 15 s covers the difference between a
                // city's nominal point and its extent without admitting a
                // genuinely wrong duration (these paths gain or lose a second
                // of totality over a few km, so a real error is far larger).
                assertTrue(
                    abs(computed - published) <= 15.0,
                    "${row.city} ${row.eclipseDate}: totality ${computed}s, published ${published}s",
                )
            }
        }
    }

    /**
     * §17.1: "2026-08-12 total eclipse: totality in northern Spain; Madrid
     * partial > 90 %; path sample near (43° N, 5° W)."
     */
    @Test
    fun august2026IsTotalInNorthernSpainAndAJustMissedPartialInMadrid() {
        val nearMiss = localCircumstancesAt(GeoPoint(40.4168, -3.7038), Instant.parse("2026-08-12T18:32:00Z"))
        assertEquals(EclipseKind.Partial, nearMiss.kind, "Madrid sits just outside the 2026 path")
        assertTrue(
            nearMiss.obscuration > 0.90,
            "§17.1: Madrid must see a partial deeper than 90 %, got ${nearMiss.obscuration}",
        )
        // The near-miss is the whole point of this spot-check: Madrid is
        // ~99.98 % covered (Wikipedia, "Solar eclipse of August 12, 2026") and
        // still not total, so a model that rounds obscuration up to totality —
        // or a path whose southern limit is drawn a few tens of km too far
        // south — fails here and nowhere else.
        assertTrue(
            nearMiss.obscuration > 0.99,
            "expected Madrid within a whisker of totality, got ${nearMiss.obscuration}",
        )

        // (43° N, 5° W) is §17.1's named northern-Spain reference point; the
        // published centreline passes 43.37 N 6.19 W two minutes earlier.
        val northernSpain = localCircumstancesAt(GeoPoint(43.0, -5.0), Instant.parse("2026-08-12T18:28:00Z"))
        assertEquals(EclipseKind.Total, northernSpain.kind, "§17.1: (43 N, 5 W) is inside the 2026 path")
        assertNotNull(totalityDurationSec(northernSpain))
    }

    /**
     * §17.1: "2027-08-02 total eclipse: totality Luxor ≈ 6 min 22 s;
     * Gibraltar-area centerline crossing."
     */
    @Test
    fun august2027IsTotalOverLuxorForThePublishedSixMinutesAndCrossesTheGibraltarArea() {
        val luxor = localCircumstancesAt(GeoPoint(25.6872, 32.6396), Instant.parse("2027-08-02T10:05:00Z"))
        assertEquals(EclipseKind.Total, luxor.kind)
        val duration = assertNotNull(totalityDurationSec(luxor))
        // 6m22s = 382 s (§17.1); Wikipedia's Espenak-derived figure for
        // greatest eclipse is 6m23s, and Luxor sits essentially on the
        // centreline near it. 10 s spans both published values — a broken
        // shadow-cone or timing model would be minutes out, not seconds.
        assertTrue(
            abs(duration - 382.0) <= 10.0,
            "Luxor totality ${duration}s, published ~382 s (6m22s)",
        )

        // The Gibraltar-area crossing: Tarifa is the mainland point the
        // centreline comes ashore at, Gibraltar itself a few km east.
        for ((name, point) in listOf("Tarifa" to GeoPoint(36.0128, -5.6065), "Gibraltar" to GeoPoint(36.1408, -5.3536))) {
            val local = localCircumstancesAt(point, Instant.parse("2027-08-02T08:47:00Z"))
            assertEquals(EclipseKind.Total, local.kind, "§17.1: $name is in the 2027 path")
            assertNotNull(totalityDurationSec(local), name)
        }
    }

    private fun localCircumstanceRows(): List<LocalCircumstanceRow> =
        Fixtures.csv("gsfc_local_solar_circumstances_named_eclipses.csv").map {
            LocalCircumstanceRow(
                eclipseDate = it.getValue("eclipse_date"),
                city = it.getValue("city"),
                point = GeoPoint(it.getValue("latitude_deg").toDouble(), it.getValue("longitude_deg").toDouble()),
                expectedLocalKind = SolarEclipseKind.valueOf(it.getValue("expected_local_kind")),
                firstContactUtc = Instant.parse(it.getValue("first_contact_utc")),
                maxUtc = Instant.parse(it.getValue("max_utc")),
                lastContactUtc = it.getValue("last_contact_utc").takeIf(String::isNotEmpty)?.let(Instant::parse),
                totalityDurationSec = it.getValue("totality_duration_sec").takeIf(String::isNotEmpty)?.toDouble(),
            )
        }

    /** The engine's own local circumstances, searched from far enough back to find [near]'s eclipse. */
    private fun localCircumstancesAt(point: GeoPoint, near: Instant) =
        searchLocalSolarEclipse((near - 6.hours).toAstroTime(), Observer(point.latDeg, point.lonDeg, 0.0))

    private fun totalityDurationSec(local: io.github.cosinekitty.astronomy.LocalSolarEclipseInfo): Double? {
        val begin = local.totalBegin?.time?.toInstant() ?: return null
        val end = local.totalEnd?.time?.toInstant() ?: return null
        return (end - begin).inWholeMilliseconds / 1000.0
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
        /** Null where the source publishes only a sunset-truncated or totality-end value. */
        val lastContactUtc: Instant?,
        /** Null where the eclipse is only partial from this city. */
        val totalityDurationSec: Double?,
    )
}
