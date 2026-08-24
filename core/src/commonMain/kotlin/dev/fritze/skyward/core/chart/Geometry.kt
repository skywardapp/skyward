package dev.fritze.skyward.core.chart

import kotlin.math.hypot

/**
 * The two-dimensional value types the chart projections speak in.
 *
 * They exist because §15.3 keeps `:core` free of Compose, and every
 * projection in this package used to return
 * `androidx.compose.ui.geometry.Offset` — a value class that is, in itself,
 * exactly this. Rather than let one geometry type drag the whole Compose
 * runtime into `commonMain`, each frontend converts at its own drawing
 * boundary; the conversion is a constructor call per point, and the
 * projections stay shared (§4.1's "small presentational helpers … as pure
 * functions"). See docs/adr/0021-chart-math-in-core.md.
 *
 * Float, not Double, because these are screen coordinates: the drawing APIs
 * on both platforms take Float, and widening only to narrow again at the
 * call site would round twice.
 */
data class ChartPoint(val x: Float, val y: Float) {

    /**
     * Vector arithmetic, as the camera maths needs it: panning adds a drag
     * delta, and zooming about a focus solves `focus - (focus - offset) * scale`.
     * These mirror the operators `Offset` carried, so the expressions that
     * moved here read unchanged.
     */
    operator fun plus(other: ChartPoint): ChartPoint = ChartPoint(x + other.x, y + other.y)

    operator fun minus(other: ChartPoint): ChartPoint = ChartPoint(x - other.x, y - other.y)

    operator fun times(scale: Float): ChartPoint = ChartPoint(x * scale, y * scale)

    /** Screen-space distance, for hit-testing a feature against a tap or click. */
    fun distanceTo(other: ChartPoint): Float = hypot(x - other.x, y - other.y)

    companion object {
        val Zero: ChartPoint = ChartPoint(0f, 0f)
    }
}

/** The pixel extent of a canvas, as the projections need it to scale into. */
data class ChartSize(val width: Float, val height: Float) {

    /** The shorter side — what a circular chart (§14.3, §14.4) sizes its radius from. */
    val minDimension: Float get() = if (width < height) width else height

    companion object {
        val Zero: ChartSize = ChartSize(0f, 0f)
    }
}
