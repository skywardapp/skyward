package dev.fritze.skyward.ui.sky.skychart

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import dev.fritze.skyward.core.chart.SkyObject
import dev.fritze.skyward.core.chart.SkyObjectKind
import dev.fritze.skyward.core.chart.SkyProjection
import dev.fritze.skyward.core.chart.SkyScene
import dev.fritze.skyward.core.chart.SkySceneBuilder
import dev.fritze.skyward.core.chart.nightAnchor
import dev.fritze.skyward.core.chart.nightWindow
import dev.fritze.skyward.core.format.formatDateTime
import dev.fritze.skyward.ui.chart.distance
import dev.fritze.skyward.ui.chart.project
import dev.fritze.skyward.ui.sky.SkyUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import kotlin.time.Instant

/**
 * §14.3's sky chart on a phone: a stereographic all-sky view for a chosen
 * saved location and instant, with a slider spanning the night.
 *
 * Starless by design (§19 R10, ADR 0022) — this is a port of the desktop
 * chart, not an occasion to revisit that. Everything drawn is something the
 * app already tracks: the Sun, the Moon, the naked-eye planets, and the
 * radiants, comets and eclipses of the occurrences in the database.
 *
 * The projection and the scene are `:core`'s (ADR 0021); what differs from
 * the desktop is that a tap opens a bottom sheet where the desktop draws
 * labels beside every marker — forty labels do not fit on a phone.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkyChartTab(state: SkyUiState, zone: TimeZone, now: Instant, onOpenEvent: (String) -> Unit) {
    if (state.locations.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "Add a saved location in Settings to draw its sky.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(24.dp),
            )
        }
        return
    }

    var locationId by remember(state.locations) {
        mutableStateOf(state.locations.firstOrNull { it.isPrimary }?.id ?: state.locations.first().id)
    }
    val location = state.locations.firstOrNull { it.id == locationId } ?: state.locations.first()

    // §14.3: the slider spans "the selected night, defaulting to next
    // astronomical darkness". Searched from local noon rather than from now:
    // keying on a ticking instant would rebuild the window and snap the
    // slider back under the user's thumb.
    val anchor = remember(now, zone) { nightAnchor(now, zone) }
    val night = remember(location, anchor) { nightWindow(location, anchor) }
    var fraction by remember(night) { mutableStateOf(0.5f) }
    val instant = night.start + (night.end - night.start) * fraction.toDouble()

    // §4.3: astronomy off the main thread. A scene is ~15 body positions
    // plus one per active occurrence, which is not frame-budget work.
    val scene by produceState<SkyScene?>(initialValue = null, location, instant, state.occurrences) {
        value = withContext(Dispatchers.Default) {
            SkySceneBuilder.build(location, instant, state.occurrences)
        }
    }

    var canvasSize by remember { mutableStateOf(Size.Zero) }
    var selected by remember { mutableStateOf<SkyObject?>(null) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (candidate in state.locations) {
                FilterChip(
                    selected = candidate.id == location.id,
                    onClick = { locationId = candidate.id },
                    label = { Text(candidate.name) },
                )
            }
        }

        Text(
            "${formatDateTime(instant, zone)} · ${night.describe(zone)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )
        Slider(
            value = fraction,
            onValueChange = { fraction = it },
            modifier = Modifier.padding(horizontal = 16.dp),
        )

        Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    // Square: the chart is a disc, and a stereographic sky is
                    // only round if its bounding box is.
                    .aspectRatio(1f)
                    .clip(CircleShape)
                    .onSizeChanged { canvasSize = it.toSize() }
                    .semantics {
                        contentDescription =
                            "All-sky chart for ${location.name}: ${scene?.objects?.size ?: 0} objects above the horizon"
                    }
                    .pointerInput(scene) {
                        detectTapGestures { position ->
                            val current = scene ?: return@detectTapGestures
                            selected = hitTest(position, current, size.toSize())
                        }
                    },
            ) {
                drawSky(scene)
            }
        }

        Text(
            "Zenith at the centre, horizon at the rim; north up, east left — a planisphere held overhead. " +
                "Starless by design. Tap a marker for detail.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp),
        )
    }

    selected?.let { obj ->
        ModalBottomSheet(onDismissRequest = { selected = null }) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(obj.label, style = MaterialTheme.typography.titleMedium)
                Text(
                    "Altitude ${obj.altitudeDeg.toInt()}° · azimuth ${obj.azimuthDeg.toInt()}°",
                    style = MaterialTheme.typography.bodyMedium,
                )
                obj.annotation?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                obj.occurrenceId?.let { id ->
                    TextButton(onClick = {
                        selected = null
                        onOpenEvent(id)
                    }) { Text("Open event") }
                }
            }
        }
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

private fun DrawScope.drawSky(scene: SkyScene?) {
    val center = Offset(size.width / 2f, size.height / 2f)
    val radius = size.minDimension / 2f - 12f

    drawRect(skyBackground(scene?.sunAltitudeDeg ?: -90.0))
    drawCircle(HORIZON, radius = radius, center = center, style = Stroke(width = 1.5f))
    for (altitude in listOf(30.0, 60.0)) {
        drawCircle(
            HORIZON.copy(alpha = 0.35f),
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
        when (obj.kind) {
            SkyObjectKind.SUN -> drawCircle(Color(0xFFFFD37A), radius = 11f, center = position)
            SkyObjectKind.MOON -> drawMoon(position, obj.phaseFraction ?: 1.0)
            SkyObjectKind.PLANET -> drawCircle(Color(0xFFE7ECF6), radius = 5f, center = position)
            SkyObjectKind.RADIANT -> drawCrosshair(position, Color(0xFF7FB4FF))
            SkyObjectKind.COMET -> {
                drawCircle(Color(0xFF9FE8E0), radius = 6f, center = position)
                drawCircle(Color(0xFF9FE8E0).copy(alpha = 0.4f), radius = 12f, center = position, style = Stroke(width = 1.5f))
            }
            SkyObjectKind.ECLIPSE -> drawCircle(
                color = Color(0xFFFFC65C),
                radius = 15f,
                center = position,
                style = Stroke(width = 2f),
            )
        }
    }
}

private val HORIZON = Color(0xFFAEBBD3)

/**
 * The Moon's phase glyph (§14.3): a full disc with the unlit part painted
 * back over it, so the drawn shape tracks the illuminated fraction instead
 * of being a generic circle.
 */
private fun DrawScope.drawMoon(center: Offset, phaseFraction: Double) {
    val radius = 10f
    drawCircle(Color(0xFFE8ECF5), radius = radius, center = center)
    val unlit = (1.0 - phaseFraction).coerceIn(0.0, 1.0)
    if (unlit <= 0.02) return
    // Approximate the terminator with an offset dark disc — accurate enough
    // at ten pixels across, and honest about which limb is lit.
    drawCircle(
        color = Color(0xFF141A26),
        radius = radius,
        center = Offset(center.x - (radius * 2 * unlit).toFloat(), center.y),
    )
    drawCircle(Color(0xFFE8ECF5).copy(alpha = 0.45f), radius = radius, center = center, style = Stroke(width = 1f))
}

private fun DrawScope.drawCrosshair(center: Offset, color: Color) {
    val arm = 10f
    drawLine(color, Offset(center.x - arm, center.y), Offset(center.x + arm, center.y), strokeWidth = 1.5f)
    drawLine(color, Offset(center.x, center.y - arm), Offset(center.x, center.y + arm), strokeWidth = 1.5f)
    drawCircle(color.copy(alpha = 0.5f), radius = arm * 0.6f, center = center, style = Stroke(width = 1f))
}

/**
 * Hit-tests against the same geometry [drawSky] uses — centre and radius are
 * derived from the canvas size identically in both, so a tap lands where the
 * marker was drawn.
 *
 * Unlike the desktop, every object is tappable rather than only the
 * occurrence-backed ones: the phone has no room for the labels the desktop
 * draws beside each marker, so the sheet is the only way to find out what a
 * dot is. Objects without an occurrence simply offer no "Open event".
 */
private fun hitTest(position: Offset, scene: SkyScene, canvasSize: Size): SkyObject? {
    val center = Offset(canvasSize.width / 2f, canvasSize.height / 2f)
    val radius = canvasSize.minDimension / 2f - 12f
    return scene.objects
        .mapNotNull { obj ->
            val projected = SkyProjection.project(obj.altitudeDeg, obj.azimuthDeg, center, radius)
                ?: return@mapNotNull null
            obj to distance(projected, position)
        }
        .filter { it.second <= HIT_RADIUS }
        .minByOrNull { it.second }
        ?.first
}

// A fingertip, not a cursor — the desktop uses 14f.
private const val HIT_RADIUS = 28f
