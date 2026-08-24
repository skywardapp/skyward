package dev.fritze.skyward.ui.chart

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.fritze.skyward.core.chart.EclipsePathPolyline
import dev.fritze.skyward.core.chart.MapCamera
import dev.fritze.skyward.core.chart.eclipsePathPolylines
import dev.fritze.skyward.core.model.Occurrence
import dev.fritze.skyward.core.model.SavedLocation
import dev.fritze.skyward.core.model.SolarEclipsePayload
import kotlin.math.ceil
import kotlin.math.floor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * §13.3's eclipse block: "path mini-map for eclipses [static canvas drawing
 * of centralPath + location markers — no tile map needed]".
 *
 * Static is the point — no pan, no zoom, no hit-testing. It answers one
 * question ("does the track come anywhere near me?") at a glance; §14.1's
 * full map on the Sky tab is where a reader goes to explore.
 *
 * "No tile map needed" rules out OSM tiles and their licensing burden, not
 * a base layer: without coastlines a bare curve on an empty rectangle says
 * nothing about *where* the eclipse is. The Natural Earth vectors are in
 * the APK now (ADR 0023), so the land layer the desktop map draws is free
 * here.
 *
 * Only TOTAL/ANNULAR/HYBRID eclipses carry a sampled central path (§7.1.3);
 * for a partial there is nothing to draw and this renders nothing.
 */
@Composable
fun EclipsePathMiniMap(occurrence: Occurrence, locations: List<SavedLocation>) {
    val payload = occurrence.payload as? SolarEclipsePayload ?: return
    if (payload.centralPath.isEmpty()) return

    val polyline = remember(occurrence.id) {
        eclipsePathPolylines(listOf(occurrence)).firstOrNull()
    } ?: return

    // Decoding half a megabyte of coastline and walking 60 000 points into a
    // Path is not UI-thread work on a phone (§19 R1 is the same concern for
    // path sampling). `landPath` is a process-wide lazy val, so this pays
    // that cost once, off the main thread, and every later chart gets it for
    // nothing; until it lands the map draws without its base layer rather
    // than blocking the detail screen.
    val land by produceState<Path?>(initialValue = null) {
        value = withContext(Dispatchers.Default) { landPath }
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Central eclipse path", style = MaterialTheme.typography.titleSmall)
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                // §14.1's equirectangular projection is 2:1 by construction:
                // 360 degrees of longitude over 180 of latitude.
                .aspectRatio(2f)
                .clip(RoundedCornerShape(8.dp))
                .semantics {
                    contentDescription = buildContentDescription(polyline, locations)
                },
        ) {
            drawMiniMap(polyline, locations, land)
        }
        Text(
            "Central path across the globe; pins are your saved locations.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * A blind user gets nothing from "world map", so name what the picture
 * actually shows — the latitude band the track crosses is the one fact the
 * drawing conveys that the times table above it does not.
 */
internal fun buildContentDescription(polyline: EclipsePathPolyline, locations: List<SavedLocation>): String {
    val points = polyline.allPoints
    // Rounded outward, not truncated: toInt() rounds toward zero, so a track
    // from -0.7° to 20.7° would be announced as "between 0 and 20 degrees" —
    // narrower than the truth, and on the wrong side of the equator.
    val north = points.maxOfOrNull { ceil(it.latDeg).toInt() }
    val south = points.minOfOrNull { floor(it.latDeg).toInt() }
    val band = if (north != null && south != null) " between latitudes $south and $north degrees" else ""
    val pins = if (locations.isEmpty()) "" else ", with ${locations.size} saved location markers"
    return "Map of the eclipse central path$band$pins"
}

/**
 * Drawn at the identity camera: the whole world, exactly filling the 2:1
 * canvas. A camera fitted to the track would zoom a narrow path to fill the
 * frame, which reads as "this is happening everywhere" — the opposite of
 * what a mini-map is for.
 */
private fun DrawScope.drawMiniMap(
    polyline: EclipsePathPolyline,
    locations: List<SavedLocation>,
    land: Path?,
) {
    val camera = MapCamera()
    drawRect(ChartPalette.Ocean)

    if (land != null) {
        val worldScale = size.height
        withTransform({ scale(worldScale, worldScale, pivot = Offset.Zero) }) {
            drawPath(land, ChartPalette.LandFill)
            drawPath(land, ChartPalette.LandStroke, style = Stroke(width = 1f / worldScale))
        }
    }

    for (segment in polyline.segments) {
        for (i in 0 until segment.size - 1) {
            drawLine(
                color = PATH_COLOR,
                start = camera.project(segment[i], size),
                end = camera.project(segment[i + 1], size),
                strokeWidth = 3f,
            )
        }
    }

    for (location in locations) {
        val center = camera.project(location.point, size)
        drawCircle(ChartPalette.Pin, radius = 4f, center = center)
        drawCircle(PIN_RING, radius = 4f, center = center, style = Stroke(width = 1.5f))
    }
}

/** The solar-eclipse lane colour from [ChartPalette], as a constant the draw pass can read. */
private val PATH_COLOR = Color(0xFFFFC65C)
private val PIN_RING = Color(0xFF0D1522)
