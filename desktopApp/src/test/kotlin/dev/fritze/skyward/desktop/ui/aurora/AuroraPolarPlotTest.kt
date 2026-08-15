package dev.fritze.skyward.desktop.ui.aurora

import androidx.compose.ui.geometry.Offset
import dev.fritze.skyward.core.model.GeoPoint
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** §14.4 Row 2's north-polar azimuthal plot. */
class AuroraPolarPlotTest {

    private val center = Offset(150f, 150f)
    private val radius = 140f

    @Test
    fun thePoleIsTheCentreAndTheRimIsFortyFiveDegrees() {
        val pole = AuroraPolarPlot.project(GeoPoint(90.0, 0.0), center, radius, north = true)!!
        assertTrue((pole - center).getDistance() < 0.01f, "pole projected to $pole")

        val rim = AuroraPolarPlot.project(GeoPoint(45.0, 40.0), center, radius, north = true)!!
        assertTrue(abs((rim - center).getDistance() - radius) < 0.01f, "45N projected to $rim")

        val midway = AuroraPolarPlot.project(GeoPoint(67.5, 0.0), center, radius, north = true)!!
        assertTrue(abs((midway - center).getDistance() - radius / 2f) < 0.01f, "67.5N should be half way out")
    }

    @Test
    fun placesOutsideThePlottedCapHaveNoPosition() {
        assertNull(AuroraPolarPlot.project(GeoPoint(40.4, 3.7), center, radius, north = true)) // Madrid
        assertNull(AuroraPolarPlot.project(GeoPoint(64.1, -21.9), center, radius, north = false)) // Reykjavik on the south view
    }

    @Test
    fun zeroLongitudePointsUpAndEastGoesClockwiseOnTheNorthView() {
        val greenwich = AuroraPolarPlot.project(GeoPoint(60.0, 0.0), center, radius, north = true)!!
        val east = AuroraPolarPlot.project(GeoPoint(60.0, 90.0), center, radius, north = true)!!

        assertTrue(greenwich.y < center.y && abs(greenwich.x - center.x) < 0.01f, "0E should be straight up, was $greenwich")
        assertTrue(east.x > center.x && abs(east.y - center.y) < 0.01f, "90E should be to the right, was $east")
    }

    @Test
    fun theSouthViewMirrorsRatherThanRotates() {
        val north = AuroraPolarPlot.project(GeoPoint(70.0, 90.0), center, radius, north = true)!!
        val south = AuroraPolarPlot.project(GeoPoint(-70.0, 90.0), center, radius, north = false)!!
        assertTrue(abs(north.y - south.y) < 0.01f, "mirroring should preserve the vertical axis")
        assertTrue(abs((north.x - center.x) + (south.x - center.x)) < 0.01f, "east should flip sides: $north vs $south")
    }

    @Test
    fun unprojectInvertsProjectInsideTheDisc() {
        for (latitude in listOf(50.0, 65.0, 80.0)) {
            for (longitude in listOf(-170.0, -30.0, 0.0, 45.0, 179.0)) {
                val point = GeoPoint(latitude, longitude)
                val projected = AuroraPolarPlot.project(point, Offset(0f, 0f), 1f, north = true)!!
                val back = AuroraPolarPlot.unproject(projected.x.toDouble(), projected.y.toDouble(), north = true)!!
                assertTrue(abs(back.latDeg - latitude) < 0.01, "lat $latitude round-tripped to ${back.latDeg}")
                assertTrue(abs(back.lonDeg - longitude) < 0.01, "lon $longitude round-tripped to ${back.lonDeg}")
            }
        }
    }

    @Test
    fun theColourRampRisesInOpacityWithProbability() {
        val low = AuroraPolarPlot.probabilityArgb(10.0) ushr 24
        val high = AuroraPolarPlot.probabilityArgb(90.0) ushr 24
        assertTrue(high > low, "expected higher probability to be more opaque, got $low then $high")
    }
}
