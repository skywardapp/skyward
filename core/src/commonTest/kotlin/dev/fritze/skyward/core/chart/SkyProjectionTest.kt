package dev.fritze.skyward.core.chart

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/** §14.3's stereographic all-sky projection. */
class SkyProjectionTest {

    private val center = ChartPoint(200f, 200f)
    private val radius = 180f

    @Test
    fun theZenithIsTheCentre() {
        val zenith = SkyProjection.project(90.0, 123.0, center, radius)!!
        assertTrue(zenith.distanceTo(center) < 0.01f, "zenith projected to $zenith")
    }

    @Test
    fun theHorizonIsTheRim() {
        for (azimuth in 0..359 step 30) {
            val point = SkyProjection.project(0.0, azimuth.toDouble(), center, radius)!!
            assertTrue(abs(point.distanceTo(center) - radius) < 0.01f, "azimuth $azimuth landed at $point")
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
    /**
     * The screen reader's object count and the projection have to agree on
     * what "above the horizon" means. `objects` holds every body the builder
     * computed — the Sun and every planet, set or not — so counting it would
     * announce roughly twice what is drawn.
     */
    @Test
    fun aboveHorizonCountsOnlyWhatTheProjectionWillDraw() {
        val scene = SkyScene(
            time = Instant.parse("2026-08-14T22:00:00Z"),
            sunAltitudeDeg = -30.0,
            objects = listOf(
                SkyObject("up", 45.0, 90.0, SkyObjectKind.PLANET),
                SkyObject("on the horizon", 0.0, 180.0, SkyObjectKind.PLANET),
                SkyObject("set", -1.0, 270.0, SkyObjectKind.PLANET),
                SkyObject("well set", -40.0, 0.0, SkyObjectKind.SUN),
            ),
        )

        assertEquals(listOf("up", "on the horizon"), scene.aboveHorizon.map { it.label })
        // The invariant that matters: exactly the objects the chart can place.
        assertEquals(
            scene.aboveHorizon,
            scene.objects.filter { SkyProjection.project(it.altitudeDeg, it.azimuthDeg, center, radius) != null },
        )
    }

}
