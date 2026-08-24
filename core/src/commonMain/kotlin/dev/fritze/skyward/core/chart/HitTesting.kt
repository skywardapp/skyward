package dev.fritze.skyward.core.chart

import dev.fritze.skyward.core.model.GeoPoint

/**
 * Picking a feature out of a chart, shared by both frontends.
 *
 * These are the "what did the user mean" rules — nearest within a
 * threshold, and which layer outranks which — as opposed to the drawing
 * that put the features on screen. They take [ChartPoint] rather than a
 * Compose type (ADR 0021), so a mouse click and a fingertip differ only in
 * the radius each caller passes.
 */

/** True when [point] projects within [radiusPx] of [position]. */
fun MapCamera.isWithin(point: GeoPoint, position: ChartPoint, size: ChartSize, radiusPx: Float): Boolean =
    project(point, size).distanceTo(position) <= radiusPx

/**
 * §14.1's map picking. Locations are checked first only to give them
 * priority when a pin sits on a path: a location is not an occurrence, so a
 * hit there returns null and swallows the click rather than opening
 * whatever is underneath it.
 *
 * A layer absent from [layers] is not drawn, so it cannot be picked either.
 */
fun mapHitTest(
    position: ChartPoint,
    size: ChartSize,
    camera: MapCamera,
    layers: Set<MapLayer>,
    locations: List<GeoPoint>,
    eonet: List<EonetMarker>,
    eclipsePaths: List<EclipsePathPolyline>,
    pinRadiusPx: Float,
    markerRadiusPx: Float,
    pathRadiusPx: Float,
): String? {
    if (MapLayer.LOCATIONS in layers && locations.any { camera.isWithin(it, position, size, pinRadiusPx) }) {
        return null
    }
    if (MapLayer.EONET in layers) {
        val marker = eonet
            .filter { camera.isWithin(it.point, position, size, markerRadiusPx) }
            .minByOrNull { camera.project(it.point, size).distanceTo(position) }
        if (marker != null) return marker.occurrenceId
    }
    if (MapLayer.ECLIPSE_PATHS in layers) {
        return eclipsePaths
            .mapNotNull { path ->
                val nearest = path.allPoints
                    .minOfOrNull { camera.project(it, size).distanceTo(position) }
                    ?: return@mapNotNull null
                if (nearest <= pathRadiusPx) path.occurrenceId to nearest else null
            }
            .minByOrNull { it.second }
            ?.first
    }
    return null
}

/**
 * §14.3's sky-chart picking, against the same geometry the chart draws
 * with: centre and radius are derived from the canvas identically in both,
 * so a tap lands where the marker was drawn.
 *
 * [occurrenceBackedOnly] is the one place the two frontends genuinely
 * differ — the desktop labels every marker beside it, so only
 * occurrence-backed objects lead anywhere; a phone has no room for those
 * labels, so every object is pickable and the sheet is how you find out
 * what a dot is.
 */
fun skyChartHitTest(
    position: ChartPoint,
    scene: SkyScene,
    canvasSize: ChartSize,
    // Passed in rather than derived: a fingertip is not a cursor, so the two
    // frontends want different tolerances for the same geometry.
    hitRadiusPx: Float,
    occurrenceBackedOnly: Boolean,
): SkyObject? {
    val center = ChartPoint(canvasSize.width / 2f, canvasSize.height / 2f)
    val radius = skyChartRadius(canvasSize)
    return scene.objects
        .filter { !occurrenceBackedOnly || it.occurrenceId != null }
        .mapNotNull { obj ->
            val projected = SkyProjection.project(obj.altitudeDeg, obj.azimuthDeg, center, radius)
                ?: return@mapNotNull null
            obj to projected.distanceTo(position)
        }
        .filter { it.second <= hitRadiusPx }
        .minByOrNull { it.second }
        ?.first
}

/** The chart radius both frontends derive from a square canvas, with its rim inset. */
fun skyChartRadius(canvasSize: ChartSize): Float = canvasSize.minDimension / 2f - RIM_INSET_PX

private const val RIM_INSET_PX = 12f
