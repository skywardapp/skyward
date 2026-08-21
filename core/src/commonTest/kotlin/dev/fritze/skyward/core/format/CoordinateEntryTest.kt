package dev.fritze.skyward.core.format

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CoordinateEntryTest {

    @Test
    fun `blank field is neither a value nor an error`() {
        for (text in listOf("", "   ")) {
            val entry = parseCoordinate(text, CoordinateAxis.LATITUDE)
            assertNull(entry.degrees, "blank should not parse to a value")
            assertNull(entry.error, "an untouched field must not be marked as wrong")
            assertTrue(!entry.isError)
        }
    }

    @Test
    fun `plain decimal degrees parse on both axes`() {
        assertEquals(52.52, parseCoordinate("52.52", CoordinateAxis.LATITUDE).degrees)
        assertEquals(13.405, parseCoordinate("13.405", CoordinateAxis.LONGITUDE).degrees)
        assertEquals(-33.87, parseCoordinate("-33.87", CoordinateAxis.LATITUDE).degrees)
    }

    @Test
    fun `surrounding whitespace is tolerated rather than rejected`() {
        assertEquals(52.52, parseCoordinate("  52.52 ", CoordinateAxis.LATITUDE).degrees)
    }

    @Test
    fun `text that is not a number reports what the field wants`() {
        val entry = parseCoordinate("52,52 N", CoordinateAxis.LATITUDE)
        assertNull(entry.degrees)
        val error = assertNotNull(entry.error)
        assertTrue("52.52" in error, "the message should show the expected format: $error")
    }

    @Test
    fun `non-finite input is rejected rather than stored`() {
        // "NaN" and "Infinity" are both accepted by toDouble(); either would
        // poison every downstream visibility computation for that location.
        for (text in listOf("NaN", "Infinity", "-Infinity")) {
            assertTrue(parseCoordinate(text, CoordinateAxis.LATITUDE).isError, "$text must not parse")
        }
    }

    @Test
    fun `latitude is bounded by the poles inclusively`() {
        assertEquals(90.0, parseCoordinate("90", CoordinateAxis.LATITUDE).degrees)
        assertEquals(-90.0, parseCoordinate("-90", CoordinateAxis.LATITUDE).degrees)
        assertTrue(parseCoordinate("90.1", CoordinateAxis.LATITUDE).isError)
        assertTrue(parseCoordinate("-90.1", CoordinateAxis.LATITUDE).isError)
    }

    @Test
    fun `longitude excludes its upper bound per GeoPoint's contract`() {
        assertEquals(-180.0, parseCoordinate("-180", CoordinateAxis.LONGITUDE).degrees)
        assertEquals(179.999, parseCoordinate("179.999", CoordinateAxis.LONGITUDE).degrees)
        assertTrue(parseCoordinate("180", CoordinateAxis.LONGITUDE).isError, "180 is the same meridian as -180 (§5)")
        assertTrue(parseCoordinate("-180.1", CoordinateAxis.LONGITUDE).isError)
    }

    @Test
    fun `a latitude out of range does not borrow the longitude message`() {
        val lat = assertNotNull(parseCoordinate("150", CoordinateAxis.LATITUDE).error)
        assertTrue("Latitude" in lat, lat)
        val lon = assertNotNull(parseCoordinate("200", CoordinateAxis.LONGITUDE).error)
        assertTrue("Longitude" in lon, lon)
    }

    @Test
    fun `flipping the sign moves between hemispheres`() {
        assertEquals("-52.52", flipCoordinateSign("52.52"))
        assertEquals("52.52", flipCoordinateSign("-52.52"))
    }

    @Test
    fun `flipping twice returns exactly what was typed`() {
        // The button edits text, not the parsed number: trailing zeroes and
        // exponent notation have to survive a round trip untouched.
        for (text in listOf("52.520", "1e2", "0")) {
            assertEquals(text, flipCoordinateSign(flipCoordinateSign(text)))
        }
    }

    @Test
    fun `surrounding whitespace does not defeat the minus`() {
        assertEquals("-52.52", flipCoordinateSign("  52.52 "))
        assertEquals("52.52", flipCoordinateSign(" -52.52 "))
    }
}
