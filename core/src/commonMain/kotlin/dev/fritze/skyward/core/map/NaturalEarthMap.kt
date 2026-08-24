package dev.fritze.skyward.core.map

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
 * Both frontends draw this now — Android's map arrived with ADR 0022 — so
 * the reader and the resource both live in `commonMain`. ADR 0023 records
 * the move, and why the resource is wired into two source sets rather than
 * copied into them.
 *
 * Binary layout, big-endian:
 *   magic "SKNE" (4 bytes) · version u16 · ringCount i32
 *   per ring: pointCount i32, then pointCount × (lon f32, lat f32)
 */
object NaturalEarthMap {

    internal const val MAGIC = "SKNE"
    internal const val SUPPORTED_VERSION = 1

    /** Decoded once per process; the geometry is a constant and costs ~0.5 MB. */
    val landRings: List<LandRing> by lazy { decode(loadNaturalEarthBytes()) }

    /**
     * Not an error worth crashing the app over: an unmapped world is a
     * degraded map, and the event layers drawn on top of it still work. The
     * desktop reader used to print to `System.err` here, which `commonMain`
     * has no equivalent for and should not grow one for — an empty list says
     * the same thing to the only caller that can act on it.
     */
    internal fun decode(bytes: ByteArray?): List<LandRing> {
        if (bytes == null || bytes.size < HEADER_BYTES) return emptyList()

        val magic = bytes.decodeToString(0, MAGIC.length)
        require(magic == MAGIC) { "not a Skyward Natural Earth resource (magic=$magic)" }
        val version = bytes.readUnsignedShort(4)
        require(version == SUPPORTED_VERSION) { "unsupported natural-earth.bin version $version" }

        val ringCount = bytes.readInt(6)
        val rings = ArrayList<LandRing>(ringCount)
        var offset = HEADER_BYTES
        repeat(ringCount) {
            val pointCount = bytes.readInt(offset)
            offset += 4
            val coordinates = FloatArray(pointCount * 2)
            for (i in coordinates.indices) {
                coordinates[i] = Float.fromBits(bytes.readInt(offset))
                offset += 4
            }
            rings += LandRing(coordinates)
        }
        return rings
    }

    private const val HEADER_BYTES = 10 // magic(4) + version(2) + ringCount(4)
}

/**
 * Big-endian reads, matching what `DataOutputStream` wrote in the Gradle
 * task. Hand-rolled because `commonMain` has no `DataInputStream`, and
 * because reading the whole half-megabyte resource into memory is fine for
 * a file that is decoded exactly once.
 */
private fun ByteArray.readInt(at: Int): Int =
    (this[at].toInt() and 0xFF shl 24) or
        (this[at + 1].toInt() and 0xFF shl 16) or
        (this[at + 2].toInt() and 0xFF shl 8) or
        (this[at + 3].toInt() and 0xFF)

private fun ByteArray.readUnsignedShort(at: Int): Int =
    (this[at].toInt() and 0xFF shl 8) or (this[at + 1].toInt() and 0xFF)

/**
 * The packaged `natural-earth.bin`, or null when it is not on the
 * classpath. `commonMain` can't reach `java.lang.Class`'s resource-loading
 * API directly (androidTarget and the desktop jvm target are distinct KMP
 * targets even though both happen to run on a JVM), hence expect/actual —
 * the same seam, for the same reason, as `loadShowersJsonText`.
 */
internal expect fun loadNaturalEarthBytes(): ByteArray?
