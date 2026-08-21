package dev.fritze.skyward.desktop.ui.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import dev.fritze.skyward.core.format.formatDayAndMonth
import dev.fritze.skyward.core.model.GeoPoint
import dev.fritze.skyward.core.model.Phenomenon
import dev.fritze.skyward.core.model.SavedLocation
import dev.fritze.skyward.desktop.ui.DesktopAppState
import dev.fritze.skyward.desktop.ui.eventdetail.EventDetailPane
import dev.fritze.skyward.desktop.ui.theme.phenomenonColor
import kotlin.math.roundToInt

/**
 * §14.1's event map: an equirectangular Canvas over Natural Earth land
 * vectors with toggleable event layers. No tiles, no network, no licensing
 * burden — the whole map is in the binary.
 */
/** Everything the canvas draws, gathered once so the draw and hit-test paths agree by construction. */
private data class MapContent(
    val landPath: androidx.compose.ui.graphics.Path,
    val eclipsePaths: List<EclipsePathPolyline>,
    val eonet: List<EonetMarker>,
    val locations: List<SavedLocation>,
    val radii: List<Double>,
    val overlay: androidx.compose.ui.graphics.ImageBitmap?,
)

@Composable
fun EventMapScreen(state: DesktopAppState) {
    val occurrences by state.occurrences.collectAsState()
    val locations by state.locations.collectAsState()
    val rules by state.allRules.collectAsState()
    val grid by state.ovationGrid.collectAsState()
    val selected = state.selectedOccurrenceId

    var camera by remember { mutableStateOf(MapCamera()) }
    var enabledLayers by remember { mutableStateOf(MapLayer.entries.toSet()) }

    // Each of these is a pure function of data that changes rarely; recomputing
    // them per frame would put GeoJSON-scale work in the draw path.
    val content = MapContent(
        landPath = landPath,
        eclipsePaths = remember(occurrences) { eclipsePathPolylines(occurrences.filter { it.phenomenon == Phenomenon.SOLAR_ECLIPSE }) },
        eonet = remember(occurrences) { eonetMarkers(occurrences) },
        locations = locations,
        radii = remember(rules) { travelRadiiKm(rules) },
        overlay = remember(grid) { grid?.let(::ovationOverlayImage) },
    )

    Row(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).fillMaxHeight()) {
            MapHeader(zoom = camera.zoom, onResetView = { camera = MapCamera() })
            LayerChips(enabledLayers) { enabledLayers = it }

            // Sized explicitly rather than with aspectRatio(): under
            // fillMaxSize() both dimensions are already fixed, so aspectRatio
            // cannot shrink the box to fit and the map spills out of its pane.
            BoxWithConstraints(Modifier.fillMaxSize().padding(12.dp), contentAlignment = Alignment.Center) {
                val mapWidth = minOf(maxWidth, maxHeight * MAP_ASPECT)
                Box(
                    modifier = Modifier
                        .width(mapWidth)
                        .height(mapWidth / MAP_ASPECT)
                        .clip(RoundedCornerShape(6.dp))
                        .background(OCEAN),
                ) {
                    Canvas(
                        modifier = Modifier
                            .fillMaxSize()
                            .mapGestures(
                                camera = camera,
                                onCameraChange = { camera = it },
                                hitTestKeys = arrayOf(occurrences, locations, enabledLayers, camera),
                                onTap = { position, size ->
                                    hitTest(position, size, camera, enabledLayers, content)?.let(state::selectOccurrence)
                                },
                            ),
                    ) {
                        drawMap(camera, content, enabledLayers, selected)
                    }

                    if (MapLayer.ECLIPSE_PATHS in enabledLayers && content.eclipsePaths.isNotEmpty()) {
                        EclipseLabels(state, content.eclipsePaths, camera)
                    }
                }
            }

            Legend(enabledLayers, hasGrid = grid != null)
        }

        if (selected != null) {
            VerticalDivider()
            Box(Modifier.width(420.dp).fillMaxHeight()) {
                EventDetailPane(state, selected, onClose = { state.selectOccurrence(null) })
            }
        }
    }
}

@Composable
private fun MapHeader(zoom: Float, onResetView: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text("Map", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Equirectangular · ${(zoom * 10).roundToInt() / 10.0}× · drag to pan, scroll to zoom",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onResetView) { Text("Reset view") }
    }
}

/**
 * §14.1's "pan/zoom via transformable state" plus click-to-detail, as one
 * modifier so the screen body isn't three nested gesture blocks deep.
 */
private fun Modifier.mapGestures(
    camera: MapCamera,
    onCameraChange: (MapCamera) -> Unit,
    hitTestKeys: Array<Any?>,
    onTap: (position: Offset, size: Size) -> Unit,
): Modifier = this
    .pointerInput(Unit) {
        detectDragGestures { change, drag ->
            change.consume()
            onCameraChange(camera.panned(drag, size.toSize()))
        }
    }
    .pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent()
                if (event.type != PointerEventType.Scroll) continue
                val change = event.changes.firstOrNull() ?: continue
                val scrollY = change.scrollDelta.y
                if (scrollY == 0f) continue
                // Wheel up (negative delta) zooms in, around the pointer.
                val factor = if (scrollY < 0) ZOOM_STEP else 1f / ZOOM_STEP
                onCameraChange(camera.zoomed(factor, change.position, size.toSize()))
                change.consume()
            }
        }
    }
    .pointerInput(keys = hitTestKeys) {
        detectTapGestures { position -> onTap(position, size.toSize()) }
    }

private const val MAP_ASPECT = 2f
private const val ZOOM_STEP = 1.2f
private val OCEAN = Color(0xFF0D1522)
private val LAND_FILL = Color(0xFF243044)
private val LAND_STROKE = Color(0xFF4A5B78)
private val TRAVEL_RADIUS = Color(0xFF8FD3C7).copy(alpha = 0.35f)
private val PIN_FILL = Color(0xFFF2F5FA)
private val PIN_OUTLINE = Color(0xFF11151E)

@Composable
private fun LayerChips(enabled: Set<MapLayer>, onChange: (Set<MapLayer>) -> Unit) {
    Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (layer in MapLayer.entries) {
            FilterChip(
                selected = layer in enabled,
                onClick = { onChange(if (layer in enabled) enabled - layer else enabled + layer) },
                label = { Text(layer.label) },
            )
        }
    }
}

/**
 * §14.1's "date labels" on eclipse paths. Drawn as composables rather than
 * canvas text so they use the app's own typography and stay legible at every
 * zoom — a label baked into the transformed canvas would scale with it.
 */
@Composable
private fun EclipseLabels(state: DesktopAppState, paths: List<EclipsePathPolyline>, camera: MapCamera) {
    Box(Modifier.fillMaxSize()) {
        BoxWithSize { size ->
            for (path in paths) {
                val anchor = path.allPoints.getOrNull(path.allPoints.size / 2) ?: continue
                val screen = camera.project(anchor, size)
                if (screen.x < 0 || screen.y < 0 || screen.x > size.width || screen.y > size.height) continue
                Text(
                    text = formatDayAndMonth(path.peakTime, state.zone),
                    style = MaterialTheme.typography.labelSmall,
                    color = phenomenonColor(Phenomenon.SOLAR_ECLIPSE),
                    modifier = Modifier.absoluteOffsetPx(screen.x, screen.y),
                )
            }
        }
    }
}

/** Small helper so the label layer can read its own pixel size without a second Canvas. */
@Composable
private fun BoxWithSize(content: @Composable (Size) -> Unit) {
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = androidx.compose.ui.platform.LocalDensity.current
        val size = with(density) { Size(maxWidth.toPx(), maxHeight.toPx()) }
        content(size)
    }
}

/** Places a label at an absolute pixel position inside the map box. */
private fun Modifier.absoluteOffsetPx(xPx: Float, yPx: Float): Modifier =
    this.then(Modifier.offset { IntOffset(xPx.roundToInt(), yPx.roundToInt()) })

@Composable
private fun Legend(layers: Set<MapLayer>, hasGrid: Boolean) {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
        Text(
            buildString {
                append("Made with Natural Earth (public domain). ")
                if (MapLayer.AURORA in layers) {
                    append(if (hasGrid) "Aurora overlay: OVATION cells at 10 % and above." else "Aurora overlay: no nowcast grid fetched yet.")
                }
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun DrawScope.drawMap(
    camera: MapCamera,
    content: MapContent,
    layers: Set<MapLayer>,
    selectedOccurrenceId: String?,
) {
    drawBaseMap(camera, content.landPath)
    if (MapLayer.AURORA in layers) content.overlay?.let { drawAuroraOverlay(camera, it) }
    if (MapLayer.ECLIPSE_PATHS in layers) drawEclipsePaths(camera, content.eclipsePaths, selectedOccurrenceId)
    if (MapLayer.EONET in layers) drawEonetMarkers(camera, content.eonet)
    if (MapLayer.LOCATIONS in layers) drawLocations(camera, content.locations, content.radii)
}

/**
 * The path is in world units, so one transform draws all 60 000 points; the
 * stroke width is divided back out so the coastline stays hairline-thin at
 * every zoom instead of growing into a smear.
 */
private fun DrawScope.drawBaseMap(camera: MapCamera, landPath: androidx.compose.ui.graphics.Path) {
    val worldScale = size.height * camera.zoom
    withTransform({
        translate(camera.offset.x, camera.offset.y)
        scale(worldScale, worldScale, pivot = Offset.Zero)
    }) {
        drawPath(landPath, LAND_FILL)
        drawPath(landPath, LAND_STROKE, style = Stroke(width = 1f / worldScale))
    }
}

private fun DrawScope.drawAuroraOverlay(camera: MapCamera, overlay: androidx.compose.ui.graphics.ImageBitmap) {
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

private fun DrawScope.drawEclipsePaths(camera: MapCamera, paths: List<EclipsePathPolyline>, selectedOccurrenceId: String?) {
    val color = phenomenonColor(Phenomenon.SOLAR_ECLIPSE)
    for (path in paths) {
        val selected = path.occurrenceId == selectedOccurrenceId
        for (segment in path.segments) {
            for (i in 0 until segment.size - 1) {
                drawLine(
                    color = if (selected) color else color.copy(alpha = 0.75f),
                    start = camera.project(segment[i], size),
                    end = camera.project(segment[i + 1], size),
                    strokeWidth = if (selected) 4f else 2.5f,
                )
            }
        }
    }
}

private fun DrawScope.drawEonetMarkers(camera: MapCamera, markers: List<EonetMarker>) {
    val color = phenomenonColor(Phenomenon.TERRESTRIAL)
    for (marker in markers) {
        val center = camera.project(marker.point, size)
        drawCircle(color.copy(alpha = 0.9f), radius = 4f, center = center)
        drawCircle(color, radius = 7f, center = center, style = Stroke(width = 1.5f))
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
        drawCircle(PIN_FILL, radius = 5f, center = center)
        drawCircle(PIN_OUTLINE, radius = 5f, center = center, style = Stroke(width = 2f))
    }
}

/**
 * Click targets, nearest-first within a pixel threshold. Locations are
 * checked first only to give them priority when a pin sits on a path; a
 * location is not an occurrence, so a hit there returns null and simply
 * swallows the click rather than selecting whatever is underneath it.
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
                path.allPoints
                    .minOfOrNull { distance(camera.project(it, size), position) }
                    ?.takeIf { it <= PATH_HIT_RADIUS }
                    ?.let { path.occurrenceId to it }
            }
            .minByOrNull { it.second }
            ?.first
    }
    return null
}

private fun MapCamera.isWithin(point: GeoPoint, position: Offset, size: Size, radiusPx: Float): Boolean =
    distance(project(point, size), position) <= radiusPx

private const val PIN_HIT_RADIUS = 10f
private const val MARKER_HIT_RADIUS = 10f
private const val PATH_HIT_RADIUS = 8f
