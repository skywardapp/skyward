package dev.fritze.skyward.desktop.ui.map

import androidx.compose.ui.geometry.Size
import dev.fritze.skyward.core.model.GeoPoint
import dev.fritze.skyward.core.model.SolarEclipseKind
import dev.fritze.skyward.core.model.SolarEclipsePayload
import dev.fritze.skyward.core.model.TimeWindow
import dev.fritze.skyward.core.sources.DerivedThresholds
import dev.fritze.skyward.core.sources.EclipseSource
import dev.fritze.skyward.core.sources.RefreshRequest
import dev.fritze.skyward.core.sources.SourceSettings
import dev.fritze.skyward.core.visibility.haversineDistanceKm
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * §18's M6 acceptance criterion: "eclipse path for 2027-08-02 renders
 * correctly vs reference map".
 *
 * Reference figures for the total solar eclipse of 2 August 2027 are
 * Espenak/NASA GSFC's, as reproduced in Wikipedia's "Solar eclipse of August
 * 2, 2027": greatest eclipse at 25.5° N, 33.2° E at 10:07 UTC with 6 min
 * 23 s of totality and a path ~258 km wide, tracking east from southern
 * Spain and Morocco across Algeria, Libya and Egypt into Saudi Arabia,
 * Yemen and Somalia. The centreline is famously right over Luxor.
 *
 * The test asserts both halves of "renders correctly": that the sampled
 * geometry matches the published track, and that projecting it through
 * [MapCamera] puts it where a reader of that map would expect — west to
 * east, unbroken, and over the right cities.
 */
class EclipsePathRenderingTest {

    /** Places on or very close to the published centreline, west to east. */
    private val centrelineReferences = listOf(
        "Tarifa, Spain" to GeoPoint(36.01, -5.61),
        "Tangier, Morocco" to GeoPoint(35.76, -5.83),
        "Benghazi, Libya" to GeoPoint(32.12, 20.07),
        "Luxor, Egypt" to GeoPoint(25.69, 32.64),
        "Jeddah, Saudi Arabia" to GeoPoint(21.49, 39.19),
    )

    /**
     * Half the published path width is ~129 km; the sampler emits one point
     * per 2-minute bucket, which at shadow speed is another ~100 km of
     * along-track granularity. 300 km therefore says "this city is on the
     * track" without asserting a precision the sampling does not claim.
     */
    private val toleranceKm = 300.0

    @Test
    fun august2027PathTracksThePublishedCentreline() = runTest(timeout = 300.seconds) {
        val payload = fetchEclipsePayload()

        assertTrue(payload.kind == SolarEclipseKind.TOTAL, "expected a total eclipse, got ${payload.kind}")
        assertTrue(
            abs(payload.greatestEclipsePoint.latDeg - 25.5) < 1.5,
            "greatest eclipse latitude ${payload.greatestEclipsePoint.latDeg}, expected ~25.5",
        )
        assertTrue(
            abs(payload.greatestEclipsePoint.lonDeg - 33.2) < 1.5,
            "greatest eclipse longitude ${payload.greatestEclipsePoint.lonDeg}, expected ~33.2",
        )

        val path = payload.centralPath.map { it.point }
        assertTrue(path.size >= 10, "expected a sampled centreline, got ${path.size} points")

        for ((name, reference) in centrelineReferences) {
            val nearest = path.minOf { haversineDistanceKm(it, reference) }
            assertTrue(nearest <= toleranceKm, "$name is ${nearest.toInt()} km from the sampled path, expected <= ${toleranceKm.toInt()} km")
        }
    }

    @Test
    fun august2027PathProjectsAsOneUnbrokenWestToEastTrack() = runTest(timeout = 300.seconds) {
        val payload = fetchEclipsePayload()
        val polyline = eclipsePathPolylines(listOf(occurrenceFor(payload))).single()
        val camera = MapCamera()
        val size = Size(1280f, 640f)

        // This eclipse never crosses the antimeridian, so the renderer must
        // not have split it — a spurious split is exactly how a wrap bug
        // shows up on screen.
        assertTrue(polyline.segments.size == 1, "expected one continuous segment, got ${polyline.segments.size}")

        val screenPoints = polyline.allPoints.map { camera.project(it, size) }
        val strictlyEastward = screenPoints.zipWithNext().count { (a, b) -> b.x > a.x }
        assertTrue(
            strictlyEastward >= screenPoints.size - 2,
            "expected a west-to-east track; only $strictlyEastward of ${screenPoints.size - 1} steps moved east",
        )

        // No two consecutive samples should jump across the map: at 2-minute
        // sampling the shadow covers a couple of degrees, i.e. a handful of
        // pixels at 1x.
        val biggestJump = screenPoints.zipWithNext().maxOf { (a, b) -> (b - a).getDistance() }
        assertTrue(biggestJump < 80f, "path jumps ${biggestJump}px between samples — that would draw as a streak")

        // And the projected track must pass through the projected cities.
        for ((name, reference) in centrelineReferences) {
            val referencePixel = camera.project(reference, size)
            val nearestPixels = screenPoints.minOf { (it - referencePixel).getDistance() }
            // 300 km is ~2.7° of latitude; at this viewport one degree of
            // latitude is 640/180 ≈ 3.6 px, so allow ~12 px.
            assertTrue(nearestPixels <= 12f, "$name projects ${nearestPixels}px from the drawn path")
        }
    }

    private suspend fun fetchEclipsePayload(): SolarEclipsePayload {
        val start = Instant.parse("2027-07-20T00:00:00Z")
        val end = Instant.parse("2027-08-20T00:00:00Z")
        val result = EclipseSource().refresh(
            RefreshRequest(
                now = start,
                horizon = TimeWindow(start, end),
                locations = emptyList(),
                state = emptyMap(),
                settings = SourceSettings(),
                derivedThresholds = DerivedThresholds(null, null, null),
            ),
        )
        val eclipse = result.occurrences.first { it.id == ECLIPSE_ID }
        return eclipse.payload as SolarEclipsePayload
    }

    private fun occurrenceFor(payload: SolarEclipsePayload) = dev.fritze.skyward.core.model.Occurrence(
        id = ECLIPSE_ID,
        phenomenon = dev.fritze.skyward.core.model.Phenomenon.SOLAR_ECLIPSE,
        sourceId = "eclipse",
        title = "Total solar eclipse",
        window = TimeWindow(payload.greatestEclipseTime, payload.greatestEclipseTime),
        peakTime = payload.greatestEclipseTime,
        certainty = dev.fritze.skyward.core.model.Certainty.CERTAIN,
        payload = payload,
        fetchedAt = payload.greatestEclipseTime,
        expiresAt = null,
    )

    private companion object {
        const val ECLIPSE_ID = "se:20270802"
    }
}
