package dev.fritze.skyward.desktop.ui.map

import dev.fritze.skyward.core.map.NaturalEarthMap
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The bundled base map (§14.1) and the Compose `Path` built from it.
 *
 * Separate from `:core`'s `MapLayersTest`, which covers the projection and
 * layer geometry: these two assertions need the Natural Earth resource and a
 * Compose `Path` respectively, so they belong to the frontend that has both.
 */
class MapBaseLayerTest {

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
}
