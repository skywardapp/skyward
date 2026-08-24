package dev.fritze.skyward.core.chart

import dev.fritze.skyward.core.model.GeoPoint
import dev.fritze.skyward.core.model.Occurrence
import dev.fritze.skyward.core.model.SolarEclipsePayload
import dev.fritze.skyward.core.model.TerrestrialPayload
import dev.fritze.skyward.core.rules.Cond
import dev.fritze.skyward.core.rules.Rule
import dev.fritze.skyward.core.visibility.toRadians
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
fun travelCircleRadii(center: GeoPoint, radiusKm: Double, camera: MapCamera, size: ChartSize): Pair<Float, Float> {
    val latDegrees = radiusKm / KM_PER_LAT_DEGREE
    // Near the poles cos(lat) collapses and the ellipse would grow without
    // bound; capping it keeps the drawing sane without pretending the
    // approximation still holds there.
    val lonDegrees = radiusKm / (KM_PER_LAT_DEGREE * max(cos(center.latDeg.toRadians()), 0.05))
    return Pair(
        (lonDegrees * camera.pixelsPerLonDegree(size)).toFloat(),
        (latDegrees * camera.pixelsPerLatDegree(size)).toFloat(),
    )
}
