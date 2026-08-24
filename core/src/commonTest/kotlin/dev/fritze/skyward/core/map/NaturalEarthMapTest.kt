package dev.fritze.skyward.core.map

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The bundled Natural Earth base layer (§14.1).
 *
 * `tools/naturalearth/README.md` has told anyone refreshing the vintage to
 * re-run this test since M6; it did not exist until ADR 0023 moved the
 * reader into `commonMain`. It runs on both the JVM and Android, which is
 * also the check that the resource actually reaches the APK — the decode
 * returns an empty list when the file is missing rather than throwing, so
 * a packaging mistake shows up here as "0 rings" instead of a blank map at
 * runtime.
 */
class NaturalEarthMapTest {

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

    /**
     * The decoder against a hand-built resource, which the `DataInputStream`
     * version could not be tested against without a file on disk. Big-endian
     * byte order is the whole contract between the Gradle task's
     * `DataOutputStream` and this reader, and getting it backwards would
     * still "work" — it would just draw nonsense.
     */
    @Test
    fun theDecoderReadsBigEndianRingsBackOut() {
        val expected = floatArrayOf(-179.5f, -89.25f, 0f, 0f, 12.75f, 51.5f)
        val decoded = NaturalEarthMap.decode(resourceOf(listOf(expected)))

        val ring = decoded.single()
        assertEquals(3, ring.pointCount)
        assertEquals(-179.5f, ring.lon(0))
        assertEquals(-89.25f, ring.lat(0))
        assertEquals(12.75f, ring.lon(2))
        assertEquals(51.5f, ring.lat(2))
    }

    @Test
    fun aMissingResourceDegradesToAnEmptyMapRatherThanThrowing() {
        assertTrue(NaturalEarthMap.decode(null).isEmpty())
        assertTrue(NaturalEarthMap.decode(ByteArray(0)).isEmpty(), "a truncated header must not index past the end")
    }

    private fun resourceOf(rings: List<FloatArray>): ByteArray {
        val bytes = mutableListOf<Byte>()
        NaturalEarthMap.MAGIC.encodeToByteArray().forEach { bytes += it }
        bytes += (NaturalEarthMap.SUPPORTED_VERSION shr 8).toByte()
        bytes += NaturalEarthMap.SUPPORTED_VERSION.toByte()
        bytes.addInt(rings.size)
        for (ring in rings) {
            bytes.addInt(ring.size / 2)
            for (value in ring) bytes.addInt(value.toRawBits())
        }
        return bytes.toByteArray()
    }

    private fun MutableList<Byte>.addInt(value: Int) {
        this += (value shr 24).toByte()
        this += (value shr 16).toByte()
        this += (value shr 8).toByte()
        this += value.toByte()
    }
}
