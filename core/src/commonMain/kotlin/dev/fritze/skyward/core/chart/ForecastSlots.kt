package dev.fritze.skyward.core.chart

import dev.fritze.skyward.core.model.AuroraForecastKind
import dev.fritze.skyward.core.model.AuroraPayload
import dev.fritze.skyward.core.model.Occurrence
import kotlin.time.Instant

/** One 3-hour bucket of §14.4's "24×3h bar strip". */
data class ForecastSlot(val start: Instant, val kp: Double?)

/**
 * The next three days in 3-hour buckets.
 *
 * Buckets with no stored forecast are left null on purpose: §7.3.3 only
 * persists slots at or above the rules' own Kp threshold, so an empty bucket
 * means "below everything you asked about", not "no data" — and both
 * dashboards' captions say so rather than drawing a confident zero.
 */
fun forecastSlots(auroraOccurrences: List<Occurrence>, now: Instant): List<ForecastSlot> {
    val byStart = auroraOccurrences
        .filter { (it.payload as? AuroraPayload)?.forecastKind == AuroraForecastKind.THREE_DAY }
        .associateBy({ it.window.start.epochSeconds / SLOT_SECONDS }) { (it.payload as AuroraPayload).kpForecast }
    val firstSlot = (now.epochSeconds / SLOT_SECONDS) * SLOT_SECONDS
    return (0 until SLOT_COUNT).map { index ->
        val start = Instant.fromEpochSeconds(firstSlot + index * SLOT_SECONDS)
        ForecastSlot(start, byStart[start.epochSeconds / SLOT_SECONDS])
    }
}

private const val SLOT_SECONDS = 3 * 3600L
private const val SLOT_COUNT = 24
