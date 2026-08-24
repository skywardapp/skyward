package dev.fritze.skyward.ui.sky.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import dev.fritze.skyward.core.chart.EclipsePathPolyline
import dev.fritze.skyward.core.chart.EonetMarker
import dev.fritze.skyward.core.chart.MapCamera
import dev.fritze.skyward.core.chart.MapLayer
import dev.fritze.skyward.core.chart.eclipsePathPolylines
import dev.fritze.skyward.core.chart.eonetMarkers
import dev.fritze.skyward.core.chart.travelRadiiKm
import dev.fritze.skyward.core.model.GeoPoint
import dev.fritze.skyward.core.model.Phenomenon
import dev.fritze.skyward.core.model.SavedLocation
import dev.fritze.skyward.ui.chart.ChartPalette
import dev.fritze.skyward.ui.chart.distance
import dev.fritze.skyward.ui.chart.landPath
import dev.fritze.skyward.ui.chart.ovationOverlayImage
import dev.fritze.skyward.ui.chart.panned
import dev.fritze.skyward.ui.chart.project
import dev.fritze.skyward.ui.chart.travelCircleRadii
import dev.fritze.skyward.ui.chart.zoomed
import dev.fritze.skyward.ui.sky.SkyUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * §14.1's event map on a phone: the same equirectangular Canvas over the
 * same Natural Earth vectors as the desktop, with pinch-zoom instead of a
 * scroll wheel and a tap-through to the detail route instead of a side pane.
 *
 * The projection, the layer geometry and the overlay pixels are all `:core`
 * (ADR 0021), so what is here is the drawing and the gestures.
 */
private data class MapContent(
    val land: Path?,
    val eclipsePaths: List<EclipsePathPolyline>,
    val eonet: List<EonetMarker>,
    val locations: List<SavedLocation>,
    val radii: List<Double>,
    val overlay: ImageBitmap?,
)

@Composable
fun EventMapTab(state: SkyUiState, onOpenEvent: (String) -> Unit) {
    var camera by remember { mutableStateOf(MapCamera()) }
    var enabledLayers by remember { mutableStateOf(MapLayer.entries.toSet()) }
    var canvasSize by remember { mutableStateOf(Size.Zero) }

    // Decoding the coastlines and walking 60 000 points into a Path is not
    // UI-thread work on a phone (§19 R1). Until it lands the event layers
    // draw over bare ocean rather than blocking the tab.
    val land by produceState<Path?>(initialValue = null) {
        value = withContext(Dispatchers.Default) { landPath }
    }
    val overlay by produceState<ImageBitmap?>(initialValue = null, state.ovationGrid) {
        val grid = state.ovationGrid
        value = if (grid == null) null else withContext(Dispatchers.Default) { ovationOverlayImage(grid) }
    }

    val content = MapContent(
        land = land,
        eclipsePaths = remember(state.occurrences) {
            eclipsePathPolylines(state.occurrences.filter { it.phenomenon == Phenomenon.SOLAR_ECLIPSE })
        },
        eonet = remember(state.occurrences) { eonetMarkers(state.occurrences) },
        locations = state.locations,
        radii = remember(state.rules) { travelRadiiKm(state.rules) },
        overlay = overlay,
    )

    Column(Modifier.fillMaxSize()) {
        MapHeader(zoom = camera.zoom, onResetView = { camera = MapCamera() })
        LayerChips(enabledLayers) { enabledLayers = it }

        Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    // §14.1's equirectangular projection is 2:1 by construction.
                    .aspectRatio(MAP_ASPECT)
                    .clip(RoundedCornerShape(6.dp))
                    .background(ChartPalette.Ocean),
            ) {
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .onSizeChanged { canvasSize = it.toSize() }
                        // A canvas has no children for a screen reader to walk,
                        // so it is one unnamed node unless we name it. The layer
                        // chips and the Upcoming list remain the readable route
                        // to the same content.
                        .semantics {
                            contentDescription =
                                "World map: ${enabledLayers.size} of ${MapLayer.entries.size} layers shown"
                        }
                        .mapGestures(
                            camera = camera,
                            onCameraChange = { camera = it },
                            hitTestKeys = arrayOf(state.occurrences, state.locations, enabledLayers, camera),
                            onTap = { position, size ->
                                hitTest(position, size, camera, enabledLayers, content)?.let(onOpenEvent)
                            },
                        ),
                ) {
                    drawMap(camera, content, enabledLayers)
                }
            }
        }

        Legend(enabledLayers, hasGrid = state.ovationGrid != null, mapReady = land != null)
    }
}

@Composable
private fun MapHeader(zoom: Float, onResetView: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(
                "Equirectangular · ${(zoom * 10).roundToInt() / 10.0}×",
                style = MaterialTheme.typography.labelLarge,
            )
            Text(
                "Drag to pan, pinch to zoom, tap an event for detail",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onResetView) { Text("Reset") }
    }
}

@Composable
private fun LayerChips(enabled: Set<MapLayer>, onChange: (Set<MapLayer>) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (layer in MapLayer.entries) {
            FilterChip(
                selected = layer in enabled,
                onClick = { onChange(if (layer in enabled) enabled - layer else enabled + layer) },
                label = { Text(layer.label) },
            )
        }
    }
}

@Composable
private fun Legend(layers: Set<MapLayer>, hasGrid: Boolean, mapReady: Boolean) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            buildString {
                // §16's courtesy credit. The map draws the data, so the credit
                // belongs with it and not only in the About screen's NOTICE.
                append("Made with Natural Earth (public domain). ")
                if (!mapReady) append("Loading coastlines… ")
                if (MapLayer.AURORA in layers) {
                    append(
                        if (hasGrid) "Aurora overlay: OVATION cells at 10 % and above."
                        else "Aurora overlay: no nowcast grid fetched yet.",
                    )
                }
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * §14.1's "pan/zoom via transformable state" plus tap-to-detail.
 *
 * `detectTransformGestures` is the touch counterpart of the desktop's
 * drag-plus-scroll pair: one gesture reports pan and zoom together, and its
 * centroid is exactly the focus `MapCamera.zoomed` wants, so the same
 * camera call serves a pinch here and a wheel there.
 *
 * Keyed on `Unit` so an in-progress gesture is not interrupted by a camera
 * update; that means the block is launched once and never restarted, so it
 * must read [camera] through [rememberUpdatedState] rather than capturing
 * the parameter — otherwise every gesture after the first keeps computing
 * from the stale camera it started with.
 */
@Composable
private fun Modifier.mapGestures(
    camera: MapCamera,
    onCameraChange: (MapCamera) -> Unit,
    hitTestKeys: Array<Any?>,
    onTap: (position: Offset, size: Size) -> Unit,
): Modifier {
    val currentCamera = rememberUpdatedState(camera)
    return this
        .pointerInput(Unit) {
            detectTransformGestures { centroid, pan, zoom, _ ->
                val panned = currentCamera.value.panned(pan, size.toSize())
                onCameraChange(
                    if (zoom == 1f) panned else panned.zoomed(zoom, centroid, size.toSize()),
                )
            }
        }
        .pointerInput(keys = hitTestKeys) {
            detectTapGestures { position -> onTap(position, size.toSize()) }
        }
}

private fun DrawScope.drawMap(camera: MapCamera, content: MapContent, layers: Set<MapLayer>) {
    content.land?.let { drawBaseMap(camera, it) }
    if (MapLayer.AURORA in layers) content.overlay?.let { drawAuroraOverlay(camera, it) }
    if (MapLayer.ECLIPSE_PATHS in layers) drawEclipsePaths(camera, content.eclipsePaths)
    if (MapLayer.EONET in layers) drawEonetMarkers(camera, content.eonet)
    if (MapLayer.LOCATIONS in layers) drawLocations(camera, content.locations, content.radii)
}

/**
 * The path is in world units, so one transform draws all 60 000 points; the
 * stroke width is divided back out so the coastline stays hairline-thin at
 * every zoom instead of growing into a smear.
 */
private fun DrawScope.drawBaseMap(camera: MapCamera, land: Path) {
    val worldScale = size.height * camera.zoom
    withTransform({
        translate(camera.offset.x, camera.offset.y)
        scale(worldScale, worldScale, pivot = Offset.Zero)
    }) {
        drawPath(land, ChartPalette.LandFill)
        drawPath(land, ChartPalette.LandStroke, style = Stroke(width = 1f / worldScale))
    }
}

private fun DrawScope.drawAuroraOverlay(camera: MapCamera, overlay: ImageBitmap) {
    val topLeft = camera.project(GeoPoint(90.0, -180.0), size)
    val bottomRight = camera.project(GeoPoint(-90.0, 180.0), size)
    drawImage(
        image = overlay,
        dstOffset = IntOffset(topLeft.x.roundToInt(), topLeft.y.roundToInt()),
        dstSize = IntSize((bottomRight.x - topLeft.x).roundToInt(), (bottomRight.y - topLeft.y).roundToInt()),
        // Low: these are 1°×1° data cells, not a photograph. Smoothing them
        // would imply a resolution the OVATION model does not have.
        filterQuality = FilterQuality.Low,
    )
}

// The map paints its own ocean and landmass, so this canvas is a dark
// surface whatever the app theme is — the light ramp would draw dark on
// dark. Hence the fixed constants rather than ChartPalette's themed pair.
private fun DrawScope.drawEclipsePaths(camera: MapCamera, paths: List<EclipsePathPolyline>) {
    for (path in paths) {
        for (segment in path.segments) {
            for (i in 0 until segment.size - 1) {
                drawLine(
                    color = ECLIPSE_PATH,
                    start = camera.project(segment[i], size),
                    end = camera.project(segment[i + 1], size),
                    strokeWidth = 3f,
                )
            }
        }
    }
}

private fun DrawScope.drawEonetMarkers(camera: MapCamera, markers: List<EonetMarker>) {
    for (marker in markers) {
        val center = camera.project(marker.point, size)
        drawCircle(EONET_MARKER.copy(alpha = 0.9f), radius = 5f, center = center)
        drawCircle(EONET_MARKER, radius = 9f, center = center, style = Stroke(width = 1.5f))
    }
}

private fun DrawScope.drawLocations(camera: MapCamera, locations: List<SavedLocation>, radii: List<Double>) {
    for (location in locations) {
        val center = camera.project(location.point, size)
        for (radiusKm in radii) {
            val (rx, ry) = travelCircleRadii(location.point, radiusKm, camera, size)
            drawOval(
                color = TRAVEL_RADIUS,
                topLeft = Offset(center.x - rx, center.y - ry),
                size = Size(rx * 2, ry * 2),
                style = Stroke(width = 1.5f),
            )
        }
        drawCircle(ChartPalette.Pin, radius = 6f, center = center)
        drawCircle(ChartPalette.Ocean, radius = 6f, center = center, style = Stroke(width = 2f))
    }
}

/**
 * Tap targets, nearest-first within a threshold. The radii are larger than
 * the desktop's click thresholds because a fingertip is not a cursor —
 * Material's 48 dp minimum is about this, and a 2.5 px eclipse track would
 * otherwise be untappable.
 *
 * Locations are checked first only to give them priority when a pin sits on
 * a path; a location is not an occurrence, so a hit there returns null and
 * swallows the tap rather than opening whatever is underneath it.
 */
private fun hitTest(
    position: Offset,
    size: Size,
    camera: MapCamera,
    layers: Set<MapLayer>,
    content: MapContent,
): String? {
    if (MapLayer.LOCATIONS in layers && content.locations.any { camera.isWithin(it.point, position, size, PIN_HIT_RADIUS) }) {
        return null
    }
    if (MapLayer.EONET in layers) {
        val marker = content.eonet
            .filter { camera.isWithin(it.point, position, size, MARKER_HIT_RADIUS) }
            .minByOrNull { distance(camera.project(it.point, size), position) }
        if (marker != null) return marker.occurrenceId
    }
    if (MapLayer.ECLIPSE_PATHS in layers) {
        return content.eclipsePaths
            .mapNotNull { path ->
                val nearest = path.allPoints
                    .minOfOrNull { distance(camera.project(it, size), position) }
                    ?: return@mapNotNull null
                if (nearest <= PATH_HIT_RADIUS) path.occurrenceId to nearest else null
            }
            .minByOrNull { it.second }
            ?.first
    }
    return null
}

private fun MapCamera.isWithin(point: GeoPoint, position: Offset, size: Size, radiusPx: Float): Boolean =
    distance(project(point, size), position) <= radiusPx

private const val MAP_ASPECT = 2f

// Touch targets, not cursor targets — roughly 48 dp at typical densities.
private const val PIN_HIT_RADIUS = 28f
private const val MARKER_HIT_RADIUS = 28f
private const val PATH_HIT_RADIUS = 24f

private val ECLIPSE_PATH = Color(0xFFFFC65C)
private val EONET_MARKER = Color(0xFFE0705F)
private val TRAVEL_RADIUS = Color(0xFF8FD3C7).copy(alpha = 0.35f)
