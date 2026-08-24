package dev.fritze.skyward.ui.sky.aurora

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.fritze.skyward.core.astro.darknessWindow
import dev.fritze.skyward.core.astro.toAstroTime
import dev.fritze.skyward.core.astro.toInstant
import dev.fritze.skyward.core.chart.AuroraPolarPlot
import dev.fritze.skyward.core.chart.ForecastSlot
import dev.fritze.skyward.core.chart.forecastSlots
import dev.fritze.skyward.core.format.auroraLookDirection
import dev.fritze.skyward.core.format.formatDateTime
import dev.fritze.skyward.core.format.formatDegrees
import dev.fritze.skyward.core.format.formatKp
import dev.fritze.skyward.core.format.formatTime
import dev.fritze.skyward.core.model.AuroraPayload
import dev.fritze.skyward.core.model.Occurrence
import dev.fritze.skyward.core.model.Phenomenon
import dev.fritze.skyward.core.model.SavedLocation
import dev.fritze.skyward.core.sources.KpEstimate
import dev.fritze.skyward.core.visibility.OvationGrid
import dev.fritze.skyward.core.visibility.geomagneticLatitudeDeg
import dev.fritze.skyward.ui.chart.ChartPalette
import dev.fritze.skyward.ui.chart.gScaleLabel
import dev.fritze.skyward.ui.chart.polarRasterImage
import dev.fritze.skyward.ui.chart.project
import dev.fritze.skyward.ui.sky.SkyUiState
import io.github.cosinekitty.astronomy.Observer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.time.Instant

/**
 * §14.4's aurora dashboard on a phone.
 *
 * The desktop lays its three rows out side by side; a phone has one column,
 * so the same three become a vertical scroll: the Kp gauge and the 24×3h
 * forecast strip, the OVATION polar view, then a verdict card per saved
 * location.
 *
 * §13.3 used to answer an Android user's aurora question with an "open
 * dashboard on desktop" hint. This is that dashboard (ADR 0022).
 */
@Composable
fun AuroraDashboardTab(state: SkyUiState, zone: TimeZone, now: Instant) {
    val auroraOccurrences = remember(state.occurrences) {
        state.occurrences.filter { it.phenomenon == Phenomenon.AURORA }
    }
    val slots = remember(auroraOccurrences, now) { forecastSlots(auroraOccurrences, now) }
    val issuedAt = auroraOccurrences.mapNotNull { (it.payload as? AuroraPayload)?.issuedAt }.maxOrNull()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        KpGaugeCard(state.currentKp, zone)
        ForecastStripCard(slots, issuedAt, zone)
        PolarViewCard(state.ovationGrid, state.locations)

        Text("Your locations", style = MaterialTheme.typography.titleMedium)
        if (state.locations.isEmpty()) {
            Text("Add a location in Settings to get a verdict here.", style = MaterialTheme.typography.bodyMedium)
        }
        for (location in state.locations) {
            LocationVerdictCard(location, slots, state.currentKp, now, zone)
        }
    }
}

/** §14.4 Row 1's gauge. */
@Composable
private fun KpGaugeCard(estimate: KpEstimate?, zone: TimeZone) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Estimated Kp now", style = MaterialTheme.typography.titleSmall)
            if (estimate == null) {
                Text("No live Kp reading.", style = MaterialTheme.typography.bodyMedium)
                return@Column
            }
            // Read outside the draw lambda: `onDraw` is not a composable scope
            // and cannot resolve the palette itself.
            val bandColors = List(9) { step -> ChartPalette.kp(step + 0.5) }
            val needleColor = MaterialTheme.colorScheme.onSurface
            Box(Modifier.fillMaxWidth().height(110.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    val center = Offset(size.width / 2f, size.height * 0.95f)
                    val radius = minOf(size.width / 2f, size.height) * 0.85f
                    // A 180° dial from Kp 0 (left) to Kp 9 (right).
                    for (step in 0 until 9) {
                        drawArc(
                            color = bandColors[step],
                            startAngle = 180f + step * 20f,
                            sweepAngle = 18f,
                            useCenter = false,
                            topLeft = Offset(center.x - radius, center.y - radius),
                            size = Size(radius * 2, radius * 2),
                            style = Stroke(width = 14f),
                        )
                    }
                    val fraction = (estimate.estimatedKp / 9.0).coerceIn(0.0, 1.0)
                    val angle = (180.0 + fraction * 180.0) * kotlin.math.PI / 180.0
                    drawLine(
                        color = needleColor,
                        start = center,
                        end = Offset(
                            center.x + (radius * 0.92 * cos(angle)).toFloat(),
                            center.y + (radius * 0.92 * sin(angle)).toFloat(),
                        ),
                        strokeWidth = 3f,
                    )
                    drawCircle(needleColor, radius = 5f, center = center)
                }
            }
            Text(
                "Kp ${formatKp(estimate.estimatedKp)}" + (gScaleLabel(estimate.estimatedKp)?.let { " · $it" } ?: ""),
                style = MaterialTheme.typography.headlineSmall,
                color = ChartPalette.kp(estimate.estimatedKp),
            )
            Text(
                "SWPC 1-minute estimate, ${formatDateTime(estimate.time, zone)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private val EMPTY_SLOT_HEIGHT = 6.dp

@Composable
private fun ForecastStripCard(slots: List<ForecastSlot>, issuedAt: Instant?, zone: TimeZone) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Three-day forecast", style = MaterialTheme.typography.titleSmall)
            Row(
                Modifier.fillMaxWidth().height(100.dp),
                horizontalArrangement = Arrangement.spacedBy(1.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                for (slot in slots) {
                    val kp = slot.kp
                    Box(
                        Modifier
                            .weight(1f)
                            // A stored slot's bar is proportional to its Kp; a
                            // slot with no stored forecast still gets a visible
                            // stub, so the strip reads as "24 quiet slots"
                            // rather than as a card that failed to draw.
                            .height(if (kp == null) EMPTY_SLOT_HEIGHT else (kp / 9.0 * 80.0).dp.coerceAtLeast(3.dp))
                            .background(if (kp == null) MaterialTheme.colorScheme.surfaceVariant else ChartPalette.kp(kp)),
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatTime(slots.first().start, zone), style = MaterialTheme.typography.labelSmall)
                Text(formatDateTime(slots.last().start, zone), style = MaterialTheme.typography.labelSmall)
            }
            Text(
                buildString {
                    append(issuedAt?.let { "Based on the forecast issued ${formatDateTime(it, zone)}. " } ?: "No forecast stored yet. ")
                    append("Empty bars are slots below every enabled rule's Kp threshold — those are not stored.")
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** §14.4 Row 2: the north-polar azimuthal plot, with a south toggle. */
@Composable
private fun PolarViewCard(grid: OvationGrid?, locations: List<SavedLocation>) {
    var north by remember { mutableStateOf(true) }
    // Rasterizing 420×420 cells is not frame-budget work (§4.3, §19 R1).
    val raster by produceState<ImageBitmap?>(initialValue = null, grid, north) {
        val current = grid
        value = if (current == null) null else withContext(Dispatchers.Default) { polarRasterImage(current, north) }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("OVATION nowcast", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = north, onClick = { north = true }, label = { Text("North") })
                FilterChip(selected = !north, onClick = { north = false }, label = { Text("South") })
            }
            Box(
                Modifier.fillMaxWidth().aspectRatio(1f).background(POLAR_BACKGROUND),
                contentAlignment = Alignment.Center,
            ) {
                if (grid == null) {
                    Text(
                        "No nowcast grid fetched yet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Canvas(Modifier.fillMaxSize()) {
                        val center = Offset(size.width / 2f, size.height / 2f)
                        val radius = size.minDimension / 2f - 8f
                        raster?.let { drawPolarRaster(it, center, radius) }
                        drawPolarGraticule(center, radius)
                        drawLocationPins(locations, center, radius, north)
                    }
                }
            }
            ProbabilityColorbar()
            Text(
                "Rim = ${AuroraPolarPlot.RIM_LATITUDE.toInt()}° latitude, rings at 60° and 75°; 0° longitude points up.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun DrawScope.drawPolarRaster(raster: ImageBitmap, center: Offset, radius: Float) {
    drawImage(
        image = raster,
        dstOffset = IntOffset((center.x - radius).roundToInt(), (center.y - radius).roundToInt()),
        dstSize = IntSize((radius * 2).roundToInt(), (radius * 2).roundToInt()),
        // Low: these are model cells, not a photograph.
        filterQuality = FilterQuality.Low,
    )
}

private fun DrawScope.drawPolarGraticule(center: Offset, radius: Float) {
    for (latitude in listOf(45.0, 60.0, 75.0)) {
        val ringRadius = ((90.0 - latitude) / (90.0 - AuroraPolarPlot.RIM_LATITUDE)).toFloat() * radius
        drawCircle(GRATICULE.copy(alpha = 0.7f), radius = ringRadius, center = center, style = Stroke(width = 1f))
    }
    for (step in 0 until 4) {
        val angle = step * kotlin.math.PI / 2.0
        drawLine(
            GRATICULE.copy(alpha = 0.4f),
            start = center,
            end = Offset(
                center.x + (radius * sin(angle)).toFloat(),
                center.y - (radius * cos(angle)).toFloat(),
            ),
            strokeWidth = 1f,
        )
    }
}

private fun DrawScope.drawLocationPins(locations: List<SavedLocation>, center: Offset, radius: Float, north: Boolean) {
    for (location in locations) {
        val position = AuroraPolarPlot.project(location.point, center, radius, north) ?: continue
        drawCircle(PIN_FILL, radius = 5f, center = position)
        drawCircle(PIN_OUTLINE, radius = 5f, center = position, style = Stroke(width = 1.5f))
    }
}

// The polar plot paints its own night ground, so these stay fixed in either
// theme — same reasoning as the map's ocean.
private val POLAR_BACKGROUND = Color(0xFF0D1522)
private val GRATICULE = Color(0xFF56637C)
private val PIN_FILL = Color(0xFFF2F5FA)
private val PIN_OUTLINE = Color(0xFF11151E)

@Composable
private fun ProbabilityColorbar() {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("Overhead probability", style = MaterialTheme.typography.labelSmall)
        Canvas(Modifier.fillMaxWidth().height(14.dp)) {
            val steps = 50
            val stepWidth = size.width / steps
            for (i in 0 until steps) {
                drawRect(
                    color = Color(AuroraPolarPlot.probabilityArgb(i * 100.0 / steps)),
                    topLeft = Offset(i * stepWidth, 0f),
                    size = Size(stepWidth + 1f, size.height),
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("0 %", style = MaterialTheme.typography.labelSmall)
            Text("100 %", style = MaterialTheme.typography.labelSmall)
        }
    }
}

/**
 * §14.4 Row 3: "dipole magnetic latitude, 'visible if Kp ≥ N' (inverse of
 * §8.4's formula), current margin, darkness window tonight."
 */
@Composable
private fun LocationVerdictCard(
    location: SavedLocation,
    slots: List<ForecastSlot>,
    estimate: KpEstimate?,
    now: Instant,
    zone: TimeZone,
) {
    val geomagneticLat = remember(location) { geomagneticLatitudeDeg(location.point) }
    // §8.4 inverted: visible when |λgm| >= 66 - 2*Kp, so Kp_needed = (66 - |λgm|)/2.
    val kpNeeded = (66.0 - abs(geomagneticLat)) / 2.0
    val peakKp = slots.mapNotNull { it.kp }.maxOrNull()
    val darkness = remember(location, now) {
        // A handful of iterations, but it is still astronomy — keep it out of
        // the draw path by remembering per tick.
        darknessWindow(now.toAstroTime(), Observer(location.point.latDeg, location.point.lonDeg, 0.0))
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(location.name, style = MaterialTheme.typography.titleSmall)
            Text(
                "Geomagnetic latitude ${formatDegrees(geomagneticLat, 1)} — visible from here when Kp ≥ ${formatKp(kpNeeded)}",
                style = MaterialTheme.typography.bodyMedium,
            )
            val current = estimate?.estimatedKp
            val margin = current?.let { it - kpNeeded }
            Text(
                when {
                    kpNeeded <= 0 -> "Above the auroral boundary at any Kp."
                    margin == null && peakKp == null -> "No current Kp reading and no forecast slot above your thresholds."
                    margin != null && margin >= 0 ->
                        "Now: Kp ${formatKp(current)} — ${formatDegrees(margin * 2, 1)} of margin. " +
                            "Look ${auroraLookDirection(geomagneticLat)} after dark."
                    margin != null -> "Now: Kp ${formatKp(current)} — short by ${formatKp(abs(margin))} Kp."
                    // Reached only when there is no live reading but a forecast slot exists.
                    else -> "Forecast peak Kp ${formatKp(peakKp ?: 0.0)} over the next three days."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (margin != null && margin >= 0) {
                    ChartPalette.kp(current)
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Text(
                darkness?.let { window ->
                    "Astronomical darkness tonight ${formatTime(window.start.toInstant(), zone)}–" +
                        formatTime(window.end.toInstant(), zone)
                } ?: "No astronomical darkness in the next two days — too far into the summer at this latitude.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
