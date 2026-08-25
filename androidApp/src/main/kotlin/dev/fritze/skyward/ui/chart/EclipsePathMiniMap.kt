package dev.fritze.skyward.ui.chart

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import dev.fritze.skyward.core.chart.EclipsePathPolyline
import dev.fritze.skyward.core.chart.MapCamera
import dev.fritze.skyward.core.chart.eclipsePathPolylines
import dev.fritze.skyward.core.model.Occurrence
import dev.fritze.skyward.core.model.SavedLocation
import dev.fritze.skyward.core.model.SolarEclipsePayload
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * §13.3's eclipse block: "path mini-map for eclipses [static canvas drawing
 * of centralPath + location markers — no tile map needed]".
 *
 * It still opens on the whole world and answers one question ("does the
 * track come anywhere near me?") at a glance; §14.1's full map on the Sky
 * tab remains where a reader goes to explore *several* events at once. What
 * §13.3's "static" no longer means is that a totality track a few hundred
 * kilometres wide has to stay four screen pixels wide: a two-finger pinch
 * zooms this canvas through the same [MapCamera] the Sky tab's map uses
 * (ADR 0026).
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

    // Keyed on the occurrence: the detail route is reused for every event, so
    // an un-keyed camera would hand the next eclipse the zoom left over from
    // the last one, pointed at a track that is somewhere else entirely.
    var camera by remember(occurrence.id) { mutableStateOf(MapCamera()) }

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
        MiniMapHeader(zoom = camera.zoom, onResetView = { camera = MapCamera() })
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                // §14.1's equirectangular projection is 2:1 by construction:
                // 360 degrees of longitude over 180 of latitude.
                .aspectRatio(2f)
                .clip(RoundedCornerShape(8.dp))
                .semantics {
                    contentDescription = buildContentDescription(polyline, locations)
                }
                .pinchZoom(camera) { camera = it },
        ) {
            drawMiniMap(camera, polyline, locations, land)
        }
        Text(
            "Central path across the globe; pins are your saved locations. " +
                "Pinch with two fingers to zoom in; one finger scrolls the page.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The title, and — only once the view has left the whole-world default — the
 * zoom factor and the way back to it. Hidden at 1× because there is nothing
 * to reset and the reading is always "1×": a permanent control for a state
 * that cannot be wrong is noise on a detail screen that is mostly tables.
 *
 * The row keeps the button's height whether or not the button is there. It
 * would otherwise grow by ~20 dp on the first pinch, shoving the map out
 * from under the fingers that are still on it.
 */
@Composable
private fun MiniMapHeader(zoom: Float, onResetView: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = ButtonDefaults.MinHeight),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Central eclipse path", style = MaterialTheme.typography.titleSmall)
        if (zoom > MapCamera.MIN_ZOOM) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${(zoom * 10).roundToInt() / 10.0}×",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onResetView) { Text("Reset") }
            }
        }
    }
}

/**
 * Two fingers zoom and pan; one finger is left alone.
 *
 * §14.1's map tab can use the stock `detectTransformGestures`, because it
 * fills a tab of its own and nothing behind it wants a drag. This canvas sits
 * inside EventDetail's `LazyColumn`, where a one-finger drag is how the
 * reader scrolls past it — so a detector that claimed every drag would trap
 * the page under a map two thirds of a screen tall. Hence the pointer-count
 * gate rather than `detectTransformGestures`: events are only read (and only
 * consumed) while at least two pointers are down, so a single finger stays
 * unconsumed and reaches the list.
 *
 * `canceled` follows the stock detector: once the list has claimed the drag,
 * this gesture is over even if a second finger arrives, and the reader lifts
 * off and pinches again. Fighting the parent for a drag it already won is
 * the alternative, and it moves both.
 *
 * Keyed on `Unit` so an in-progress pinch is not interrupted by the camera
 * update it just produced; the block therefore has to read [camera] through
 * [rememberUpdatedState] rather than capture the parameter, or every gesture
 * after the first would compute from the camera it started with.
 */
@Composable
private fun Modifier.pinchZoom(camera: MapCamera, onCameraChange: (MapCamera) -> Unit): Modifier {
    val currentCamera = rememberUpdatedState(camera)
    return pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            while (true) {
                val event = awaitPointerEvent()
                // Somebody else took this drag (the list, almost always), or
                // the last finger came off: either way the gesture is done.
                if (event.changes.any { it.isConsumed }) break
                val pressed = event.changes.count { it.pressed }
                if (pressed == 0) break
                if (pressed < 2) continue

                val zoom = event.calculateZoom()
                val pan = event.calculatePan()
                if (zoom == 1f && pan == Offset.Zero) continue

                // useCurrent = false: the centroid *before* this event is
                // where the fingers were when the spread was measured, which
                // is the point `transformed` has to hold still.
                val centroid = event.calculateCentroid(useCurrent = false)
                onCameraChange(currentCamera.value.transformed(zoom, centroid, pan, size.toSize()))
                event.changes.forEach { if (it.positionChanged()) it.consume() }
            }
        }
    }
}

/**
 * A blind user gets nothing from "world map", so name what the picture
 * actually shows — the latitude band the track crosses is the one fact the
 * drawing conveys that the times table above it does not.
 *
 * Deliberately silent about the zoom: the pinch that changes it is not a
 * gesture TalkBack can make, and announcing a state the listener cannot
 * reach or alter is worse than not mentioning it. The band described here is
 * the whole track's, at any zoom.
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
 * Drawn through [camera], which starts at the identity: the whole world,
 * exactly filling the 2:1 canvas. A camera *fitted* to the track would zoom a
 * narrow path to fill the frame, which reads as "this is happening
 * everywhere" — the opposite of what a mini-map is for. Where the reader
 * takes it from there is the reader's business.
 */
private fun DrawScope.drawMiniMap(
    camera: MapCamera,
    polyline: EclipsePathPolyline,
    locations: List<SavedLocation>,
    land: Path?,
) {
    drawRect(ChartPalette.Ocean)

    if (land != null) {
        // The path is in world units, so one transform draws all 60 000
        // points; the stroke width is divided back out so the coastline stays
        // hairline-thin at every zoom instead of growing into a smear.
        val worldScale = size.height * camera.zoom
        withTransform({
            translate(camera.offset.x, camera.offset.y)
            scale(worldScale, worldScale, pivot = Offset.Zero)
        }) {
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
