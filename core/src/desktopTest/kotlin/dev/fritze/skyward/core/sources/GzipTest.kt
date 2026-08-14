package dev.fritze.skyward.core.sources

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

class GzipTest {
    @Test
    fun roundTripsAndShrinksAMostlyZeroGridLikeOvationActuallyProduces() {
        // §7.3.1: OVATION probability cells are mostly 0 (no aurora activity
        // almost everywhere) with sparse nonzero patches -- unlike uniform
        // random bytes, this is what the real payload actually compresses well.
        val original = ByteArray(360 * 181)
        val rng = Random(42)
        repeat(500) { original[rng.nextInt(original.size)] = rng.nextInt(0, 101).toByte() }

        val compressed = gzipCompress(original)
        assertTrue(compressed.size < original.size, "a sparse grid should shrink under gzip")
        assertContentEquals(original, gzipDecompress(compressed))
    }

    @Test
    fun roundTripsEmptyInput() {
        assertContentEquals(ByteArray(0), gzipDecompress(gzipCompress(ByteArray(0))))
    }
}
