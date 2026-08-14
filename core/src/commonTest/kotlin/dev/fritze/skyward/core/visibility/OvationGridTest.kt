package dev.fritze.skyward.core.visibility

import dev.fritze.skyward.core.model.GeoPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Instant

class OvationGridTest {

    private val t = Instant.parse("2026-08-13T00:00:00Z")

    /** Builds a 360x181 grid where each cell's value comes from [valueAt]. */
    private fun buildGrid(valueAt: (lon0to359: Int, latDeg: Int) -> Int): OvationGrid {
        val bytes = ByteArray(360 * 181)
        for (lon in 0 until 360) {
            for (lat in -90..90) {
                bytes[(lon * 181) + (lat + 90)] = valueAt(lon, lat).toByte()
            }
        }
        return OvationGrid(t, t, bytes)
    }

    @Test
    fun rejectsAWronglySizedGrid() {
        assertFailsWith<IllegalArgumentException> { OvationGrid(t, t, ByteArray(10)) }
    }

    @Test
    fun rawCellLookupMatchesConstructionValues() {
        val grid = buildGrid { lon, lat -> (lon + lat) % 100 }
        assertEquals((10 + 20) % 100, grid.probabilityAt(10, 20))
        assertEquals((359 - 90) % 100, grid.probabilityAt(359, -90))
    }

    @Test
    fun rawCellLookupWrapsLongitude() {
        val grid = buildGrid { lon, _ -> if (lon == 0) 1 else 0 }
        // lon=360 should behave as lon=0.
        assertEquals(1, grid.probabilityAt(360, 0))
        assertEquals(1, grid.probabilityAt(0, 0))
    }

    @Test
    fun uniformGridInterpolatesToTheConstant() {
        val grid = buildGrid { _, _ -> 42 }
        assertEquals(42.0, grid.probabilityAt(GeoPoint(0.0, 0.0)), 1e-9)
        assertEquals(42.0, grid.probabilityAt(GeoPoint(89.9, 179.4)), 1e-9)
        assertEquals(42.0, grid.probabilityAt(GeoPoint(-90.0, -37.3)), 1e-9)
    }

    @Test
    fun interpolatesLinearlyBetweenExactVertices() {
        // Value ramps with longitude only: this isolates the lonFrac term.
        val grid = buildGrid { lon, _ -> lon % 100 }
        // Halfway between lon=10 (value 10) and lon=11 (value 11).
        assertEquals(10.5, grid.probabilityAt(GeoPoint(0.0, 10.5)), 1e-9)
        // A quarter of the way.
        assertEquals(10.25, grid.probabilityAt(GeoPoint(0.0, 10.25)), 1e-9)
    }

    @Test
    fun interpolatesAcrossTheAntimeridianWrap() {
        // lon=359 -> 80, lon=0 -> 60; a point at 359.5 should sit halfway.
        val grid = buildGrid { lon, _ -> if (lon == 359) 80 else if (lon == 0) 60 else 0 }
        assertEquals(70.0, grid.probabilityAt(GeoPoint(0.0, 359.5)), 1e-9)
        // Longitude expressed as -0.5 (== 359.5) must resolve identically.
        assertEquals(70.0, grid.probabilityAt(GeoPoint(0.0, -0.5)), 1e-9)
    }

    @Test
    fun bilinearInterpolationMatchesHandComputationAtAllFourDistinctCorners() {
        // p00=0 (lon0,lat0), p10=10 (lon1,lat0), p01=20 (lon0,lat1), p11=40 (lon1,lat1).
        val grid = buildGrid { lon, lat ->
            when {
                lon == 5 && lat == 5 -> 0
                lon == 6 && lat == 5 -> 10
                lon == 5 && lat == 6 -> 20
                lon == 6 && lat == 6 -> 40
                else -> -1 // never sampled by this test
            }
        }
        // Quarter-way in lon, half-way in lat:
        //   top = 0 + (10-0)*0.25 = 2.5
        //   bottom = 20 + (40-20)*0.25 = 25.0
        //   result = 2.5 + (25.0-2.5)*0.5 = 13.75
        assertEquals(13.75, grid.probabilityAt(GeoPoint(5.5, 5.25)), 1e-9)
    }

    @Test
    fun doesNotThrowAtThePoles() {
        val grid = buildGrid { _, _ -> 33 }
        assertEquals(33.0, grid.probabilityAt(GeoPoint(90.0, 0.0)), 1e-9)
        assertEquals(33.0, grid.probabilityAt(GeoPoint(-90.0, 0.0)), 1e-9)
    }
}
