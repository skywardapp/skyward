package dev.fritze.skyward.desktop.ui.skychart

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import dev.fritze.skyward.core.astro.darknessWindow
import dev.fritze.skyward.core.astro.toAstroTime
import dev.fritze.skyward.core.astro.toInstant
import dev.fritze.skyward.core.format.formatDateTime
import dev.fritze.skyward.core.format.formatDegrees
import dev.fritze.skyward.core.format.formatTime
import dev.fritze.skyward.core.model.SavedLocation
import dev.fritze.skyward.desktop.ui.DesktopAppState
import dev.fritze.skyward.desktop.ui.eventdetail.EventDetailPane
import io.github.cosinekitty.astronomy.Observer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/**
 * §14.3's sky chart: a stereographic all-sky view for a chosen saved
 * location and instant, with a slider spanning the night — defaulting to the
 * next astronomical darkness.
 *
 * Starless by design (§19 R10). Everything drawn here is something the app
 * is already tracking: the Sun, the Moon, the naked-eye planets, and the
 * radiants/comets/eclipses of the occurrences in the DB.
 */
@Composable
fun SkyChartScreen(state: DesktopAppState) {
    val locations by state.locations.collectAsState()
    val occurrences by state.occurrences.collectAsState()
    val now by state.tick.collectAsState()
    val selected = state.selectedOccurrenceId

    var locationId by remember(locations) { mutableStateOf(locations.firstOrNull { it.isPrimary }?.id ?: locations.firstOrNull()?.id) }
    val location = locations.firstOrNull { it.id == locationId }

    if (location == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Add a saved location in Settings to draw its sky.", style = MaterialTheme.typography.bodyMedium)
        }
        return
    }

    // §14.3: the slider spans "the selected night, defaulting to next
    // astronomical darkness". Falling back to a fixed evening window keeps
    // the chart usable at high summer latitudes, where there is no
    // astronomical night at all to span.
    //
    // Searched from local noon rather than from `now`: keying on the ticking
    // instant would rebuild the window every minute and snap the slider back
    // to the middle of the night under the user's hand, and starting the
    // search at whatever time the chart happened to be opened would give the
    // same night a different window depending on when you looked.
    val anchor = remember(now, state.zone) { nightAnchor(now, state.zone) }
    val night = remember(location, anchor) { nightWindow(location, anchor) }
    var fraction by remember(night) { mutableStateOf(0.5f) }
    val instant = night.start + (night.end - night.start) * fraction.toDouble()

    val scene by produceState<SkyScene?>(initialValue = null, location, instant, occurrences) {
        value = withContext(Dispatchers.Default) { SkySceneBuilder.build(location, instant, occurrences) }
    }

    Row(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).fillMaxHeight().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Sky chart", style = MaterialTheme.typography.headlineSmall)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (candidate in locations) {
                    FilterChip(
                        selected = candidate.id == location.id,
                        onClick = { locationId = candidate.id },
                        label = { Text(candidate.name) },
                    )
                }
            }

            Text(
                "${formatDateTime(instant, state.zone)} · ${night.describe(state)}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Slider(value = fraction, onValueChange = { fraction = it })

            var chartSize by remember { mutableStateOf(Size.Zero) }
            // `fillMaxSize().aspectRatio(1f)` fixes both dimensions before
            // aspectRatio gets a say, so the "square" comes out as wide as the
            // pane and overflows its height. Sizing it explicitly to the
            // smaller dimension is the only thing that actually fits it.
            BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                val side = minOf(maxWidth, maxHeight)
                Box(
                    Modifier
                        .size(side)
                        .clip(CircleShape)
                        .background(skyBackground(scene?.sunAltitudeDeg ?: -18.0))
                        .onSizeChanged { chartSize = it.toSize() },
                ) {
                    val currentScene = scene
                    Canvas(
                        Modifier.fillMaxSize().pointerInput(currentScene) {
                            detectTapGestures { position ->
                                val hit = currentScene?.let { hitTest(position, it, size.toSize()) }
                                hit?.occurrenceId?.let(state::selectOccurrence)
                            }
                        },
                    ) {
                        drawSky(currentScene, selected)
                    }
                    // Labels as composables rather than canvas text: they need
                    // the app's typography, and §14.3 asks for cardinal labels
                    // and per-radiant annotations, not just glyphs.
                    SkyLabels(currentScene, chartSize)
                }
            }
        }

        if (selected != null) {
            VerticalDivider()
            Box(Modifier.width(420.dp).fillMaxHeight()) {
                EventDetailPane(state, selected, onClose = { state.selectOccurrence(null) })
            }
        }
    }
}

/**
 * §14.3's "horizon circle with cardinal labels" plus each drawn object's
 * name and, for radiants, the expected-rate annotation.
 */
@Composable
private fun SkyLabels(scene: SkyScene?, canvasSize: Size) {
    if (canvasSize.minDimension <= 0f) return
    val center = Offset(canvasSize.width / 2f, canvasSize.height / 2f)
    val radius = canvasSize.minDimension / 2f - 12f

    for ((label, azimuth) in SkyProjection.CARDINALS) {
        // Pulled a little inside the rim so the glyph sits on the horizon
        // circle rather than being clipped by the round chart edge.
        val position = SkyProjection.project(4.0, azimuth, center, radius) ?: continue
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = Color(0xFFD7DEEC),
            modifier = Modifier.absoluteOffsetPx(position.x - 6f, position.y - 8f),
        )
    }

    for (obj in scene?.objects.orEmpty()) {
        if (obj.kind == SkyObjectKind.ECLIPSE) continue // its Sun/Moon marker is already labelled
        val position = SkyProjection.project(obj.altitudeDeg, obj.azimuthDeg, center, radius) ?: continue
        Column(modifier = Modifier.absoluteOffsetPx(position.x + 10f, position.y - 8f)) {
            Text(obj.label, style = MaterialTheme.typography.labelSmall, color = Color(0xFFE7ECF6))
            obj.annotation?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = Color(0xFFB6BDCB))
            }
        }
    }
}

private fun Modifier.absoluteOffsetPx(xPx: Float, yPx: Float): Modifier =
    this.then(Modifier.offset { IntOffset(xPx.roundToInt(), yPx.roundToInt()) })

/** The window the slider spans, and whether it is real astronomical darkness or the fallback. */
private data class NightWindow(val start: Instant, val end: Instant, val isAstronomicalDarkness: Boolean) {
    fun describe(state: DesktopAppState): String = if (isAstronomicalDarkness) {
        "astronomical darkness ${formatTime(start, state.zone)}–${formatTime(end, state.zone)}"
    } else {
        "no astronomical darkness tonight — showing ${formatTime(start, state.zone)}–${formatTime(end, state.zone)}"
    }
}

/**
 * Local noon of the day whose *night* the chart is showing — today's if it is
 * already afternoon, yesterday's otherwise, so someone looking at 01:00 gets
 * the night in progress rather than the next one.
 *
 * Noon is the useful anchor: searching forward from it lands on tonight's
 * darkness whatever the hour, and the value is constant for a whole
 * noon-to-noon period, which is what keeps the slider still.
 */
internal fun nightAnchor(now: Instant, zone: TimeZone): Instant {
    val local = now.toLocalDateTime(zone)
    val day = if (local.hour >= 12) local.date else local.date.minus(1, DateTimeUnit.DAY)
    return LocalDateTime(day, LocalTime(12, 0)).toInstant(zone)
}

/** [anchor] is local noon (see [nightAnchor]), never the current instant. */
private fun nightWindow(location: SavedLocation, anchor: Instant): NightWindow {
    val observer = Observer(location.point.latDeg, location.point.lonDeg, 0.0)
    val darkness = darknessWindow(anchor.toAstroTime(), observer)
    return if (darkness != null) {
        NightWindow(darkness.start.toInstant(), darkness.end.toInstant(), isAstronomicalDarkness = true)
    } else {
        // High-summer latitudes: no astronomical night to span, so show the
        // conventional 18:00–06:00 evening instead. Derived from the anchor,
        // so it is the same window all night.
        NightWindow(anchor + 6.hours, anchor + 18.hours, isAstronomicalDarkness = false)
    }
}

/** §14.3: "background gradient by sun altitude (day/twilight/night)". */
private fun skyBackground(sunAltitudeDeg: Double): Brush {
    val colors = when {
        sunAltitudeDeg > 0 -> Color(0xFF6FA8DC) to Color(0xFFBBD6F0)
        sunAltitudeDeg > -6 -> Color(0xFF2C4E7A) to Color(0xFFB07A62) // civil twilight, sunset band low down
        sunAltitudeDeg > -12 -> Color(0xFF16294A) to Color(0xFF3D4E77) // nautical
        sunAltitudeDeg > -18 -> Color(0xFF0C1830) to Color(0xFF1B2A47) // astronomical
        else -> Color(0xFF05070F) to Color(0xFF0C1220) // full night
    }
    return Brush.verticalGradient(listOf(colors.first, colors.second))
}

private fun DrawScope.drawSky(scene: SkyScene?, selectedOccurrenceId: String?) {
    val center = Offset(size.width / 2f, size.height / 2f)
    val radius = size.minDimension / 2f - 12f

    // Horizon circle and the 30°/60° altitude rings (§14.3).
    drawCircle(HORIZON, radius = radius, center = center, style = Stroke(width = 2f))
    for (altitude in listOf(30.0, 60.0)) {
        drawCircle(
            color = HORIZON.copy(alpha = 0.35f),
            radius = SkyProjection.ringRadius(altitude, radius),
            center = center,
            style = Stroke(width = 1f),
        )
    }
    for ((_, azimuth) in SkyProjection.CARDINALS) {
        val edge = SkyProjection.project(0.0, azimuth, center, radius) ?: continue
        drawLine(HORIZON.copy(alpha = 0.3f), start = center, end = edge, strokeWidth = 1f)
    }

    if (scene == null) return

    for (obj in scene.objects) {
        val position = SkyProjection.project(obj.altitudeDeg, obj.azimuthDeg, center, radius) ?: continue
        val selected = obj.occurrenceId != null && obj.occurrenceId == selectedOccurrenceId
        when (obj.kind) {
            SkyObjectKind.SUN -> drawCircle(Color(0xFFFFD37A), radius = 10f, center = position)
            SkyObjectKind.MOON -> drawMoon(position, obj.phaseFraction ?: 1.0)
            SkyObjectKind.PLANET -> {
                drawCircle(Color(0xFFE7ECF6), radius = 4f, center = position)
            }
            SkyObjectKind.RADIANT -> drawCrosshair(position, Color(0xFF7FB4FF), selected)
            SkyObjectKind.COMET -> {
                drawCircle(Color(0xFF9FE8E0), radius = 5f, center = position)
                drawCircle(Color(0xFF9FE8E0).copy(alpha = 0.4f), radius = 11f, center = position, style = Stroke(width = 1.5f))
            }
            SkyObjectKind.ECLIPSE -> drawCircle(
                color = Color(0xFFFFC65C),
                radius = 14f,
                center = position,
                style = Stroke(width = if (selected) 3f else 2f),
            )
        }
    }
}

private val HORIZON = Color(0xFFAEBBD3)

/**
 * The Moon's phase glyph (§14.3): a full disc with the unlit part painted
 * back over it, so the drawn shape actually tracks the illuminated fraction
 * instead of being a generic circle.
 */
private fun DrawScope.drawMoon(center: Offset, phaseFraction: Double) {
    val radius = 9f
    drawCircle(Color(0xFFE8ECF5), radius = radius, center = center)
    val unlit = (1.0 - phaseFraction).coerceIn(0.0, 1.0)
    if (unlit <= 0.02) return
    // Approximate the terminator with an offset dark disc — accurate enough
    // at nine pixels across, and honest about which limb is lit.
    drawCircle(
        color = Color(0xFF141A26),
        radius = radius,
        center = Offset(center.x - (radius * 2 * unlit).toFloat(), center.y),
    )
    drawCircle(Color(0xFFE8ECF5).copy(alpha = 0.45f), radius = radius, center = center, style = Stroke(width = 1f))
}

private fun DrawScope.drawCrosshair(center: Offset, color: Color, selected: Boolean) {
    val arm = if (selected) 12f else 9f
    drawLine(color, Offset(center.x - arm, center.y), Offset(center.x + arm, center.y), strokeWidth = 1.5f)
    drawLine(color, Offset(center.x, center.y - arm), Offset(center.x, center.y + arm), strokeWidth = 1.5f)
    drawCircle(color.copy(alpha = 0.5f), radius = arm * 0.6f, center = center, style = Stroke(width = 1f))
}

/**
 * Hit-tests against the same geometry [drawSky] uses — centre and radius are
 * derived from the canvas size identically in both, so a click lands where
 * the marker was drawn.
 */
private fun hitTest(position: Offset, scene: SkyScene, canvasSize: Size): SkyObject? {
    val center = Offset(canvasSize.width / 2f, canvasSize.height / 2f)
    val radius = canvasSize.minDimension / 2f - 12f
    return scene.objects
        // Only occurrence-backed objects lead anywhere; a planet is not an event.
        .filter { it.occurrenceId != null }
        .mapNotNull { obj ->
            val projected = SkyProjection.project(obj.altitudeDeg, obj.azimuthDeg, center, radius) ?: return@mapNotNull null
            obj to (projected - position).getDistance()
        }
        .filter { it.second <= HIT_RADIUS }
        .minByOrNull { it.second }
        ?.first
}

private const val HIT_RADIUS = 14f
