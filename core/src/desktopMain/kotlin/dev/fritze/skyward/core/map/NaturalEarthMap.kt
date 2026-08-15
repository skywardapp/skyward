package dev.fritze.skyward.core.map

import java.io.DataInputStream

/**
 * One closed polygon ring in geographic coordinates, stored as a flat
 * `[lon0, lat0, lon1, lat1, …]` array.
 *
 * Flat rather than a list of point objects because the renderer walks it
 * once per frame to build a `Path`: 60k points as boxed pairs would be 60k
 * allocations and a lot of pointer chasing for data that never changes.
 */
class LandRing(val coordinates: FloatArray) {
    val pointCount: Int get() = coordinates.size / 2

    fun lon(index: Int): Float = coordinates[index * 2]
    fun lat(index: Int): Float = coordinates[index * 2 + 1]
}

/**
 * §14.1's base map layer: Natural Earth 1:50m land polygons, decoded from
 * the binary resource produced at build time by `:core`'s
 * `convertNaturalEarth` task (see `tools/naturalearth/README.md`).
 *
 * Desktop-only by design — §14.1 is a desktop view, and the resource is on
 * the desktop target's classpath alone.
 */
object NaturalEarthMap {

    private const val RESOURCE = "/natural-earth.bin"
    private const val MAGIC = "SKNE"
    private const val SUPPORTED_VERSION = 1

    /** Decoded once per process; the geometry is a constant and costs ~0.5 MB. */
    val landRings: List<LandRing> by lazy { load() }

    private fun load(): List<LandRing> {
        val stream = NaturalEarthMap::class.java.getResourceAsStream(RESOURCE)
        if (stream == null) {
            // Not an error worth crashing the app over: an unmapped world is a
            // degraded map, and the event layers on top of it still work.
            System.err.println("natural-earth.bin missing from the classpath; map base layer disabled")
            return emptyList()
        }

        return DataInputStream(stream.buffered()).use { input ->
            val magic = ByteArray(4).also(input::readFully).decodeToString()
            require(magic == MAGIC) { "not a Skyward Natural Earth resource (magic=$magic)" }
            val version = input.readUnsignedShort()
            require(version == SUPPORTED_VERSION) { "unsupported natural-earth.bin version $version" }

            val ringCount = input.readInt()
            ArrayList<LandRing>(ringCount).apply {
                repeat(ringCount) {
                    val pointCount = input.readInt()
                    val coordinates = FloatArray(pointCount * 2)
                    for (i in coordinates.indices) coordinates[i] = input.readFloat()
                    add(LandRing(coordinates))
                }
            }
        }
    }
}
