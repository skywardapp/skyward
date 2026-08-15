package dev.fritze.skyward.desktop.ui.map

import androidx.compose.ui.geometry.Size
import dev.fritze.skyward.core.map.NaturalEarthMap
import dev.fritze.skyward.core.model.Certainty
import dev.fritze.skyward.core.model.GeoPoint
import dev.fritze.skyward.core.model.Occurrence
import dev.fritze.skyward.core.model.PathSample
import dev.fritze.skyward.core.model.Phenomenon
import dev.fritze.skyward.core.model.SolarEclipseKind
import dev.fritze.skyward.core.model.SolarEclipsePayload
import dev.fritze.skyward.core.model.TimeWindow
import dev.fritze.skyward.core.rules.Anchor
import dev.fritze.skyward.core.rules.Cond
import dev.fritze.skyward.core.rules.NotifySchedule
import dev.fritze.skyward.core.rules.Rule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/** §14.1's layer preparation: the pure half of the map, and the bundled base data. */
class MapLayersTest {

    private val now = Instant.parse("2027-08-02T10:00:00Z")

    private fun eclipse(points: List<GeoPoint>) = Occurrence(
        id = "se:test",
        phenomenon = Phenomenon.SOLAR_ECLIPSE,
        sourceId = "eclipse",
        title = "Total solar eclipse",
        window = TimeWindow(now - 3.days, now + 3.days),
        peakTime = now,
        certainty = Certainty.CERTAIN,
        payload = SolarEclipsePayload(
            kind = SolarEclipseKind.TOTAL,
            greatestEclipsePoint = points.firstOrNull() ?: GeoPoint(0.0, 0.0),
            greatestEclipseTime = now,
            centralPath = points.mapIndexed { index, point -> PathSample(now + index.days, point, null, 120.0) },
            obscurationAtGreatest = 1.0,
        ),
        fetchedAt = now,
        expiresAt = null,
    )

    @Test
    fun aPathIsSplitWhereItCrossesTheAntimeridian() {
        val polyline = eclipsePathPolylines(
            listOf(eclipse(listOf(GeoPoint(10.0, 170.0), GeoPoint(11.0, 179.0), GeoPoint(12.0, -178.0), GeoPoint(13.0, -170.0)))),
        ).single()

        assertEquals(2, polyline.segments.size, "expected the wrap to break the polyline")
        assertEquals(2, polyline.segments[0].size)
        assertEquals(2, polyline.segments[1].size)
        // No point is dropped by the split — only the streaking segment is.
        assertEquals(4, polyline.allPoints.size)
    }

    @Test
    fun partialEclipsesContributeNoPath() {
        val partial = eclipse(emptyList()).let { occurrence ->
            occurrence.copy(
                payload = (occurrence.payload as SolarEclipsePayload).copy(kind = SolarEclipseKind.PARTIAL, centralPath = emptyList()),
            )
        }
        assertTrue(eclipsePathPolylines(listOf(partial)).isEmpty())
    }

    @Test
    fun travelRadiiComeFromEnabledRulesOnly() {
        val enabled = rule("a", enabled = true, Cond.And(listOf(Cond.ReachableWithin(500.0), Cond.VisibleAtLocation())))
        val nested = rule("b", enabled = true, Cond.Not(Cond.Or(listOf(Cond.ReachableWithin(200.0)))))
        val disabled = rule("c", enabled = false, Cond.ReachableWithin(9999.0))

        assertEquals(listOf(200.0, 500.0), travelRadiiKm(listOf(enabled, nested, disabled)))
    }

    @Test
    fun travelCirclesStretchInLongitudeAsLatitudeRises() {
        val camera = MapCamera()
        val size = Size(1280f, 640f)
        val (equatorX, equatorY) = travelCircleRadii(GeoPoint(0.0, 0.0), 500.0, camera, size)
        val (arcticX, arcticY) = travelCircleRadii(GeoPoint(70.0, 0.0), 500.0, camera, size)

        // Latitude degrees are a constant number of kilometres; longitude
        // degrees shrink with cos(lat), which is precisely the distortion the
        // equirectangular projection does not correct.
        assertTrue(kotlin.math.abs(equatorY - arcticY) < 0.01f, "vertical radius should not depend on latitude")
        assertTrue(arcticX > equatorX * 2.5f, "expected a much wider ellipse at 70N: $equatorX -> $arcticX")
        assertTrue(equatorX > 0f && equatorY > 0f)
    }

    @Test
    fun theBundledBaseMapCoversTheWholeGlobe() {
        val rings = NaturalEarthMap.landRings
        assertTrue(rings.size > 100, "expected the Natural Earth 1:50m land layer, got ${rings.size} rings")

        var minLon = Float.MAX_VALUE
        var maxLon = -Float.MAX_VALUE
        var minLat = Float.MAX_VALUE
        var maxLat = -Float.MAX_VALUE
        var points = 0
        for (ring in rings) {
            for (i in 0 until ring.pointCount) {
                val lon = ring.lon(i)
                val lat = ring.lat(i)
                if (lon < minLon) minLon = lon
                if (lon > maxLon) maxLon = lon
                if (lat < minLat) minLat = lat
                if (lat > maxLat) maxLat = lat
                points++
            }
        }

        assertTrue(points > 20_000, "expected 1:50m detail, got $points points")
        // Coordinates must be plain degrees — a unit or sign mistake in the
        // build-time converter would show up here rather than as a blank map.
        assertTrue(minLon >= -180.1f && maxLon <= 180.1f, "longitudes out of range: $minLon..$maxLon")
        assertTrue(minLat >= -90.1f && maxLat <= 90.1f, "latitudes out of range: $minLat..$maxLat")
        assertTrue(minLat < -60f, "expected Antarctica in the base layer, southernmost was $minLat")
        assertTrue(maxLat > 75f, "expected the high Arctic in the base layer, northernmost was $maxLat")
    }

    @Test
    fun theBaseMapPathIsBuiltInWorldUnits() {
        // Two units wide, one tall — the 2:1 world the map viewport draws.
        val bounds = buildLandPath().getBounds()
        assertTrue(bounds.left >= -0.01f && bounds.right <= 2.01f, "x out of world range: ${bounds.left}..${bounds.right}")
        assertTrue(bounds.top >= -0.01f && bounds.bottom <= 1.01f, "y out of world range: ${bounds.top}..${bounds.bottom}")
    }

    private fun rule(id: String, enabled: Boolean, condition: Cond) = Rule(
        id = id,
        name = id,
        enabled = enabled,
        phenomena = setOf(Phenomenon.SOLAR_ECLIPSE),
        locationIds = null,
        condition = condition,
        schedule = NotifySchedule(emptyList(), Anchor.PEAK, notifyOnFirstSeen = false, quietHours = null),
        hidden = false,
        createdAt = now,
        modifiedAt = now,
    )
}
