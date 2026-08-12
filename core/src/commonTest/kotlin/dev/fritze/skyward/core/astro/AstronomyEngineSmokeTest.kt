package dev.fritze.skyward.core.astro

import io.github.cosinekitty.astronomy.Time
import io.github.cosinekitty.astronomy.moonPhase
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * M0 acceptance check (§18): the vendored Astronomy Engine must compile and run
 * in commonTest on both the androidTarget and desktop jvm targets. This does not
 * assert astronomical correctness — that's §17.1's golden-test job in M1.
 */
class AstronomyEngineSmokeTest {

    @Test
    fun moonPhaseIsInValidRange() {
        // 2026-08-12T00:00:00Z, an arbitrary known instant.
        val time = Time.fromMillisecondsSince1970(1786492800000L)
        val phase = moonPhase(time)
        assertTrue(phase in 0.0..360.0, "moonPhase() returned $phase, expected a degree value in [0, 360]")
    }
}
