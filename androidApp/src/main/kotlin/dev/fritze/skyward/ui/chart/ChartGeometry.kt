package dev.fritze.skyward.ui.chart

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
 * The seam between Compose's geometry value classes and `:core`'s, on
 * Android.
 *
 * The desktop app carries the same file for the same reason (ADR 0021):
 * §15.3 keeps Compose out of `:core`, so the projections speak
 * [ChartPoint]/[ChartSize] and each frontend converts where it draws. The
 * two copies cannot be one — the apps build against different Compose
 * distributions (the AndroidX BOM here, Compose Multiplatform there), and
 * §4.1 allows no module to hold shared UI code — but each is a page of
 * two-float conversions with no behaviour to drift.
 */
fun Offset.toChartPoint(): ChartPoint = ChartPoint(x, y)

fun ChartPoint.toOffset(): Offset = Offset(x, y)

fun Size.toChartSize(): ChartSize = ChartSize(width, height)

fun ChartSize.toSize(): Size = Size(width, height)

// --- §14.1 event map ---

fun MapCamera.project(point: GeoPoint, size: Size): Offset =
    project(point, size.toChartSize()).toOffset()

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
