package dev.fritze.skyward.desktop.ui.common

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import dev.fritze.skyward.core.chart.AuroraPolarPlot
import dev.fritze.skyward.core.chart.ChartPoint
import dev.fritze.skyward.core.chart.ChartSize
import dev.fritze.skyward.core.chart.MapCamera
import dev.fritze.skyward.core.chart.SkyProjection
import dev.fritze.skyward.core.chart.travelCircleRadii
import dev.fritze.skyward.core.model.GeoPoint

/**
 * The seam between Compose's geometry value classes and `:core`'s.
 *
 * §15.3 keeps Compose out of `:core`, so the chart projections speak
 * [ChartPoint]/[ChartSize] (see docs/adr/0021-chart-math-in-core.md). Both
 * sides are two floats, so converting is free — but doing it by hand at
 * forty call sites inside `DrawScope` bodies would bury the drawing in
 * noise.
 *
 * These overloads take the Compose types a `DrawScope` already has and
 * return Compose types the draw calls already want, so the rendering code
 * reads exactly as it did before the projections moved. Kotlin picks them
 * over the `:core` members by parameter type, never ambiguously: the member
 * takes [ChartSize], the extension takes [Size].
 */
fun Offset.toChartPoint(): ChartPoint = ChartPoint(x, y)

fun ChartPoint.toOffset(): Offset = Offset(x, y)

fun Size.toChartSize(): ChartSize = ChartSize(width, height)

fun ChartSize.toSize(): Size = Size(width, height)

// --- §14.1 event map ---

fun MapCamera.project(point: GeoPoint, size: Size): Offset =
    project(point, size.toChartSize()).toOffset()

fun MapCamera.project(lonDeg: Double, latDeg: Double, size: Size): Offset =
    project(lonDeg, latDeg, size.toChartSize()).toOffset()

fun MapCamera.panned(delta: Offset, size: Size): MapCamera =
    panned(delta.toChartPoint(), size.toChartSize())

fun MapCamera.zoomed(factor: Float, focus: Offset, size: Size): MapCamera =
    zoomed(factor, focus.toChartPoint(), size.toChartSize())

fun travelCircleRadii(center: GeoPoint, radiusKm: Double, camera: MapCamera, size: Size): Pair<Float, Float> =
    travelCircleRadii(center, radiusKm, camera, size.toChartSize())

// --- §14.3 sky chart ---

fun SkyProjection.project(altitudeDeg: Double, azimuthDeg: Double, center: Offset, radiusPx: Float): Offset? =
    project(altitudeDeg, azimuthDeg, center.toChartPoint(), radiusPx)?.toOffset()

// --- §14.4 aurora polar view ---

fun AuroraPolarPlot.project(point: GeoPoint, center: Offset, radiusPx: Float, north: Boolean): Offset? =
    project(point, center.toChartPoint(), radiusPx, north)?.toOffset()
