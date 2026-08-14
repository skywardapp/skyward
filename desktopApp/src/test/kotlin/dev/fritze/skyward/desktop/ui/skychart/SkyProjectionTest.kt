package dev.fritze.skyward.desktop.ui.skychart

import androidx.compose.ui.geometry.Offset
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** §14.3's stereographic all-sky projection. */
class SkyProjectionTest {

    private val center = Offset(200f, 200f)
    private val radius = 180f

    @Test
    fun theZenithIsTheCentre() {
        val zenith = SkyProjection.project(90.0, 123.0, center, radius)!!
        assertTrue((zenith - center).getDistance() < 0.01f, "zenith projected to $zenith")
    }

    @Test
    fun theHorizonIsTheRim() {
        for (azimuth in 0..359 step 30) {
            val point = SkyProjection.project(0.0, azimuth.toDouble(), center, radius)!!
            assertTrue(abs((point - center).getDistance() - radius) < 0.01f, "azimuth $azimuth landed at $point")
        }
    }

    @Test
    fun northIsUpAndEastIsLeft() {
        val north = SkyProjection.project(0.0, 0.0, center, radius)!!
        val east = SkyProjection.project(0.0, 90.0, center, radius)!!
        val south = SkyProjection.project(0.0, 180.0, center, radius)!!
        val west = SkyProjection.project(0.0, 270.0, center, radius)!!

        assertTrue(north.y < center.y, "north should be above the centre, was $north")
        assertTrue(south.y > center.y, "south should be below the centre, was $south")
        // Mirrored relative to a map of the ground — this chart is looked up at.
        assertTrue(east.x < center.x, "east should be left of the centre, was $east")
        assertTrue(west.x > center.x, "west should be right of the centre, was $west")
    }

    @Test
    fun objectsBelowTheHorizonAreNotDrawn() {
        assertNull(SkyProjection.project(-0.5, 45.0, center, radius))
        assertNull(SkyProjection.project(-40.0, 200.0, center, radius))
    }

    @Test
    fun altitudeRingsShrinkTowardTheZenith() {
        val thirty = SkyProjection.ringRadius(30.0, radius)
        val sixty = SkyProjection.ringRadius(60.0, radius)
        assertTrue(sixty < thirty && thirty < radius, "expected 60 < 30 < horizon, got $sixty $thirty $radius")
        // Stereographic, not equidistant: the 30-degree ring sits outside the
        // half-radius an equidistant projection would put it at.
        assertTrue(thirty > radius * 0.5f, "expected stereographic spacing, got $thirty for radius $radius")
    }
}
