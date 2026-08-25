package dev.fritze.skyward.core.chart

import dev.fritze.skyward.core.model.GeoPoint
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** §14.1's projection arithmetic — the part of the map that can be wrong silently. */
class MapCameraTest {

    private val size = ChartSize(1280f, 640f) // the 2:1 viewport the map is drawn into
    private val camera = MapCamera()

    @Test
    fun cornersAndCentreProjectWhereEquirectangularSays() {
        assertOffset(ChartPoint(0f, 0f), camera.project(GeoPoint(90.0, -180.0), size))
        assertOffset(ChartPoint(1280f, 640f), camera.project(GeoPoint(-90.0, 180.0), size))
        assertOffset(ChartPoint(640f, 320f), camera.project(GeoPoint(0.0, 0.0), size))
    }

    @Test
    fun projectionIsInvertible() {
        val zoomed = MapCamera(zoom = 3.4f).panned(ChartPoint(-500f, -250f), size)
        for (point in listOf(GeoPoint(51.5, -0.12), GeoPoint(-33.9, 151.2), GeoPoint(25.7, 32.6))) {
            val roundTripped = zoomed.unproject(zoomed.project(point, size), size)
            assertTrue(abs(roundTripped.latDeg - point.latDeg) < 1e-3, "lat drifted: $roundTripped vs $point")
            assertTrue(abs(roundTripped.lonDeg - point.lonDeg) < 1e-3, "lon drifted: $roundTripped vs $point")
        }
    }

    @Test
    fun zoomIsClampedToTheDocumentedRange() {
        var zoomedIn = camera
        repeat(40) { zoomedIn = zoomedIn.zoomed(1.5f, ChartPoint(640f, 320f), size) }
        assertEquals(MapCamera.MAX_ZOOM, zoomedIn.zoom)

        var zoomedOut = zoomedIn
        repeat(40) { zoomedOut = zoomedOut.zoomed(1 / 1.5f, ChartPoint(640f, 320f), size) }
        assertEquals(MapCamera.MIN_ZOOM, zoomedOut.zoom)
    }

    @Test
    fun zoomingKeepsThePointUnderTheCursorFixed() {
        val focus = ChartPoint(900f, 200f)
        val before = camera.unproject(focus, size)
        val after = camera.zoomed(2f, focus, size).unproject(focus, size)
        assertTrue(abs(after.latDeg - before.latDeg) < 1e-2, "$after vs $before")
        assertTrue(abs(after.lonDeg - before.lonDeg) < 1e-2, "$after vs $before")
    }

    @Test
    fun panningNeverRevealsSpaceBeyondTheMap() {
        val panned = MapCamera(zoom = 2f).panned(ChartPoint(5000f, 5000f), size)
        assertTrue(panned.offset.x <= 0f && panned.offset.y <= 0f, "top-left escaped: ${panned.offset}")

        val pannedOther = MapCamera(zoom = 2f).panned(ChartPoint(-5000f, -5000f), size)
        // At 2x the world is 2560x1280, so the furthest the offset may go is
        // viewport - world = -1280, -640.
        assertTrue(pannedOther.offset.x >= size.width - size.width * 2f, "bottom-right escaped: ${pannedOther.offset}")
        assertTrue(pannedOther.offset.y >= size.height - size.height * 2f, "bottom-right escaped: ${pannedOther.offset}")
    }

    @Test
    fun atOneToOneZoomThereIsNothingToPan() {
        assertEquals(ChartPoint.Zero, camera.panned(ChartPoint(300f, -120f), size).offset)
    }

    /**
     * The reason `transformed` exists: a pinch reports its zoom and its
     * centroid movement in the same event, and folding the pan into the
     * offset before the zoom multiplies it by the scale factor. Two fingers
     * that spread by 2× while their centroid slides 100 px must move the map
     * by exactly those 100 px, not 200.
     */
    @Test
    fun aPinchThatAlsoDragsMovesTheMapByThePanItMeasured() {
        val focus = ChartPoint(640f, 320f)
        val pan = ChartPoint(-100f, -40f)

        val transformed = camera.transformed(2f, focus, pan, size)
        val zoomedOnly = camera.zoomed(2f, focus, size)

        assertOffset(zoomedOnly.offset + pan, transformed.offset)
    }

    @Test
    fun aTwoFingerDragWithNoSpreadIsAPlainPan() {
        val panned = MapCamera(zoom = 2f).panned(ChartPoint(-70f, -30f), size)
        val transformed = MapCamera(zoom = 2f).transformed(1f, ChartPoint(10f, 10f), ChartPoint(-70f, -30f), size)
        assertEquals(panned, transformed)
    }

    /** Clamping still applies, so a pinch cannot walk the world off the viewport. */
    @Test
    fun aTransformNeverRevealsSpaceBeyondTheMap() {
        val transformed = MapCamera(zoom = 2f).transformed(1.5f, ChartPoint(0f, 0f), ChartPoint(9000f, 9000f), size)
        assertTrue(transformed.offset.x <= 0f && transformed.offset.y <= 0f, "escaped: ${transformed.offset}")
    }

    @Test
    fun antimeridianCrossingIsDetectedOnlyForTheLongWayRound() {
        assertTrue(MapCamera.crossesAntimeridian(179.0, -179.0))
        assertTrue(!MapCamera.crossesAntimeridian(-10.0, 10.0))
        assertTrue(!MapCamera.crossesAntimeridian(100.0, 179.0))
    }

    private fun assertOffset(expected: ChartPoint, actual: ChartPoint) {
        assertTrue(
            abs(expected.x - actual.x) < 0.01f && abs(expected.y - actual.y) < 0.01f,
            "expected $expected but was $actual",
        )
    }
}
