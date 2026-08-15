package dev.fritze.skyward.desktop.ui.map

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.toComposeImageBitmap
import dev.fritze.skyward.core.map.NaturalEarthMap
import dev.fritze.skyward.core.model.GeoPoint
import dev.fritze.skyward.core.model.Occurrence
import dev.fritze.skyward.core.model.SolarEclipsePayload
import dev.fritze.skyward.core.model.TerrestrialPayload
import dev.fritze.skyward.core.rules.Cond
import dev.fritze.skyward.core.rules.Rule
import dev.fritze.skyward.core.visibility.OvationGrid
import dev.fritze.skyward.desktop.ui.common.OvationRamp
import java.awt.image.BufferedImage
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.time.Instant

/** §14.1's toggleable layer set. */
enum class MapLayer(val label: String) {
    ECLIPSE_PATHS("Eclipse paths"),
    LOCATIONS("Locations & travel radius"),
    EONET("Earth events"),
    AURORA("Aurora nowcast"),
}

/** One eclipse central path, already split at the antimeridian and labelled. */
data class EclipsePathPolyline(
    val occurrenceId: String,
    val title: String,
    val peakTime: Instant,
    /** Each segment is a continuous run of points; a new segment starts wherever the path wraps. */
    val segments: List<List<GeoPoint>>,
) {
    /**
     * Flattened once, not per access: hit-testing and label placement both
     * read this while the pointer moves, and `flatten()` on every frame
     * rebuilds a few thousand points for nothing.
     */
    val allPoints: List<GeoPoint> by lazy { segments.flatten() }
}

/**
 * §14.1: "eclipse central paths within horizon (polyline + date labels,
 * click → detail)". Only TOTAL/ANNULAR/HYBRID eclipses carry a sampled
 * central path (§7.1.3); partials have nothing to draw.
 */
fun eclipsePathPolylines(occurrences: List<Occurrence>): List<EclipsePathPolyline> =
    occurrences.mapNotNull { occurrence ->
        val payload = occurrence.payload as? SolarEclipsePayload ?: return@mapNotNull null
        if (payload.centralPath.isEmpty()) return@mapNotNull null

        val segments = mutableListOf<List<GeoPoint>>()
        var current = mutableListOf<GeoPoint>()
        for (sample in payload.centralPath) {
            val previous = current.lastOrNull()
            if (previous != null && MapCamera.crossesAntimeridian(previous.lonDeg, sample.point.lonDeg)) {
                segments += current
                current = mutableListOf()
            }
            current += sample.point
        }
        if (current.isNotEmpty()) segments += current

        EclipsePathPolyline(
            occurrenceId = occurrence.id,
            title = occurrence.title,
            peakTime = occurrence.peakTime ?: payload.greatestEclipseTime,
            segments = segments,
        )
    }

/** §14.1: EONET events, positioned at their latest reported geometry. */
data class EonetMarker(val occurrenceId: String, val title: String, val categoryId: String, val point: GeoPoint)

fun eonetMarkers(occurrences: List<Occurrence>): List<EonetMarker> =
    occurrences.mapNotNull { occurrence ->
        val payload = occurrence.payload as? TerrestrialPayload ?: return@mapNotNull null
        EonetMarker(occurrence.id, occurrence.title, payload.categoryId, payload.latestGeometry)
    }

/**
 * §14.1: "travel-radius circles per rule km, drawn as geodesic-correct
 * ellipses — at this projection just approximate with lat-scaled circle".
 * The radii come from the enabled rules' own `ReachableWithin` distances, so
 * the map shows the distances the user actually asked to be told about
 * rather than an arbitrary ring.
 */
fun travelRadiiKm(rules: List<Rule>): List<Double> =
    rules.filter { it.enabled }
        .flatMap { reachableDistances(it.condition) }
        .distinct()
        .sorted()

private fun reachableDistances(condition: Cond): List<Double> = when (condition) {
    is Cond.ReachableWithin -> listOf(condition.km)
    is Cond.And -> condition.all.flatMap(::reachableDistances)
    is Cond.Or -> condition.any.flatMap(::reachableDistances)
    is Cond.Not -> reachableDistances(condition.inner)
    else -> emptyList()
}

/** Mean degrees of latitude per kilometre — good to a few tenths of a percent, which is well inside "approximate". */
private const val KM_PER_LAT_DEGREE = 111.32

/**
 * The screen-space half-axes of a [radiusKm] circle centred on [center].
 * Longitude degrees shrink with `cos(latitude)`, which is exactly the
 * distortion the equirectangular projection does not correct for — hence the
 * ellipse.
 */
fun travelCircleRadii(center: GeoPoint, radiusKm: Double, camera: MapCamera, size: Size): Pair<Float, Float> {
    val latDegrees = radiusKm / KM_PER_LAT_DEGREE
    // Near the poles cos(lat) collapses and the ellipse would grow without
    // bound; capping it keeps the drawing sane without pretending the
    // approximation still holds there.
    val lonDegrees = radiusKm / (KM_PER_LAT_DEGREE * max(cos(Math.toRadians(center.latDeg)), 0.05))
    return Pair(
        (lonDegrees * camera.pixelsPerLonDegree(size)).toFloat(),
        (latDegrees * camera.pixelsPerLatDegree(size)).toFloat(),
    )
}

/**
 * §14.1: "aurora OVATION heat overlay (current grid, alpha-blended cells
 * ≥ 10 %, geographic grid drawn directly — trivially aligned with
 * equirectangular)".
 *
 * Rendered into a 360×181 [ImageBitmap] once per grid rather than as 65 000
 * rectangles per frame; at equirectangular projection one grid cell is one
 * pixel, so scaling the image *is* the projection.
 */
fun ovationOverlayImage(grid: OvationGrid): ImageBitmap {
    val image = BufferedImage(360, 181, BufferedImage.TYPE_INT_ARGB)
    for (x in 0 until 360) {
        // The grid is indexed 0..359 east of Greenwich; the map starts at
        // -180. Rolling by half a world is the whole conversion.
        val gridLon = (x + 180) % 360
        for (y in 0 until 181) {
            val latitude = 90 - y
            val probability = grid.probabilityAt(gridLon, latitude)
            if (probability < OvationRamp.MAP_MIN_PROBABILITY) continue
            image.setRGB(x, y, OvationRamp.argb(probability.toDouble()))
        }
    }
    return image.toComposeImageBitmap()
}

/**
 * The base map as a `Path` in *world* coordinates, so it is built once and
 * reused for every frame with pan and zoom applied as a canvas transform
 * rather than by rebuilding 60 000 points.
 *
 * World coordinates are degrees over 180: x spans `[0, 2]` and y spans
 * `[0, 1]`. That makes the canvas transform a single uniform scale (the map
 * viewport is drawn 2:1, §14.1's equirectangular projection), which in turn
 * means a stroke width means the same thing horizontally and vertically —
 * it would not with an x-normalised-to-1 path on a 2:1 canvas.
 *
 * Prefer [landPath] over calling this: the vectors never change, so there is
 * no reason to walk 60 000 points again each time the Map tab is opened.
 */
fun buildLandPath(): Path {
    val path = Path()
    for (ring in NaturalEarthMap.landRings) {
        if (ring.pointCount < 2) continue
        var started = false
        var previousLon = 0f
        for (i in 0 until ring.pointCount) {
            val lon = ring.lon(i)
            val lat = ring.lat(i)
            val x = (lon + 180f) / 180f
            val y = (90f - lat) / 180f
            // A ring that wraps the antimeridian (Antarctica, Chukotka) would
            // otherwise be closed with a horizontal streak across the map.
            if (started && abs(lon - previousLon) > 180f) {
                path.moveTo(x, y)
            } else if (started) {
                path.lineTo(x, y)
            } else {
                path.moveTo(x, y)
                started = true
            }
            previousLon = lon
        }
        path.close()
    }
    return path
}

/**
 * The shared, process-wide land path. Built on first use and kept: the
 * Natural Earth vectors are a build-time resource that cannot change while
 * the app runs, and the `Path` is only ever read.
 */
val landPath: Path by lazy { buildLandPath() }

/** Screen-space distance, for hit-testing map features against a click. */
fun distance(a: Offset, b: Offset): Float = (a - b).getDistance()
