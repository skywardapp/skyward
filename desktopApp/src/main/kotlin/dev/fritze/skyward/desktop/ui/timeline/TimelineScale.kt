package dev.fritze.skyward.desktop.ui.timeline

import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * §14.2's time axis: "now → horizon end, log-ish zoom: next 60 days get half
 * the width".
 *
 * Implemented as two linear pieces rather than an actual logarithm: the
 * design's requirement is a specific *proportion* (half the width for the
 * near term), and a piecewise-linear map hits it exactly while staying
 * trivially invertible — which the hover hit-testing needs.
 */
class TimelineScale(
    val now: Instant,
    val end: Instant,
    val widthPx: Float,
    private val nearTerm: Duration = NEAR_TERM,
    private val nearTermFraction: Float = NEAR_TERM_FRACTION,
) {
    private val nearEnd: Instant = minOf(now + nearTerm, end)
    private val nearSeconds: Double = (nearEnd - now).inWholeSeconds.toDouble()
    private val farSeconds: Double = (end - nearEnd).inWholeSeconds.toDouble()

    /**
     * The near term gets its full share only when there is a far term to
     * share with; a horizon shorter than 60 days is simply linear across the
     * whole width, rather than cramming everything into the left half and
     * leaving the rest blank.
     */
    private val nearWidth: Float = if (farSeconds <= 0.0) widthPx else widthPx * nearTermFraction

    fun xOf(instant: Instant): Float {
        val clamped = instant.coerceIn(now, end)
        return if (clamped <= nearEnd) {
            if (nearSeconds <= 0.0) 0f else (((clamped - now).inWholeSeconds / nearSeconds) * nearWidth).toFloat()
        } else {
            val farFraction = (clamped - nearEnd).inWholeSeconds / farSeconds
            (nearWidth + farFraction * (widthPx - nearWidth)).toFloat()
        }
    }

    fun instantAt(x: Float): Instant {
        val clampedX = x.coerceIn(0f, widthPx)
        return if (clampedX <= nearWidth) {
            val fraction = if (nearWidth <= 0f) 0.0 else clampedX / nearWidth.toDouble()
            now + (nearSeconds * fraction).toLong().seconds
        } else {
            val fraction = (clampedX - nearWidth) / (widthPx - nearWidth).toDouble()
            nearEnd + (farSeconds * fraction).toLong().seconds
        }
    }

    /** True where the scale changes gradient — drawn as a hairline so the compression is visible, not mysterious. */
    val nearTermBoundaryX: Float? get() = if (farSeconds <= 0.0) null else nearWidth

    private fun Instant.coerceIn(minimum: Instant, maximum: Instant): Instant = when {
        this < minimum -> minimum
        this > maximum -> maximum
        else -> this
    }

    companion object {
        val NEAR_TERM = 60.days
        const val NEAR_TERM_FRACTION = 0.5f
    }
}
