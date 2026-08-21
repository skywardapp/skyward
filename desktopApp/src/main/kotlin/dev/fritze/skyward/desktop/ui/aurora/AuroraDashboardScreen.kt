package dev.fritze.skyward.desktop.ui.aurora

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import dev.fritze.skyward.core.format.formatDateTime
import dev.fritze.skyward.core.format.formatTime
import dev.fritze.skyward.core.model.AuroraForecastKind
import dev.fritze.skyward.core.model.AuroraPayload
import dev.fritze.skyward.core.model.Occurrence
import dev.fritze.skyward.core.model.Phenomenon
import dev.fritze.skyward.core.model.SavedLocation
import dev.fritze.skyward.core.sources.AuroraSource
import dev.fritze.skyward.core.sources.KpEstimate
import dev.fritze.skyward.core.sources.KpNowcast
import dev.fritze.skyward.core.visibility.OvationGrid
import dev.fritze.skyward.core.visibility.geomagneticLatitudeDeg
import dev.fritze.skyward.desktop.ui.DesktopAppState
import dev.fritze.skyward.desktop.ui.common.SectionCard
import dev.fritze.skyward.desktop.ui.common.formatDegrees
import dev.fritze.skyward.desktop.ui.common.formatKp
import dev.fritze.skyward.desktop.ui.theme.gScaleLabel
import dev.fritze.skyward.desktop.ui.theme.kpColor
import io.github.cosinekitty.astronomy.Observer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * §14.4's aurora dashboard: Kp gauge and 3-day strip, the OVATION polar
 * view, and a verdict card per saved location.
 *
 * Opening it forces the active polling tier (§7.3.2: "or the user opens the
 * aurora dashboard") — that is what makes the numbers on screen current
 * rather than up to three hours stale.
 */
@Composable
fun AuroraDashboardScreen(state: DesktopAppState) {
    val occurrences by state.occurrences.collectAsState()
    val locations by state.locations.collectAsState()
    val grid by state.ovationGrid.collectAsState()
    val now by state.tick.collectAsState()
    val refreshing by state.refreshingSources.collectAsState()
    var estimatedKp by remember { mutableStateOf<KpEstimate?>(null) }
    var estimateFailed by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        state.refreshSources(setOf(SWPC_SOURCE_ID))
        // §7.3.2: "Switch to active (poll OVATION + forecast) when ... the
        // user opens the aurora dashboard." The refresh above cannot know the
        // dashboard is open, so ask for the grid directly.
        withContext(Dispatchers.Default) {
            AuroraSource.fetchOvationGridNow(state.container.sourceStateRepo, Clock.System.now())
        }
        state.reloadOvationGrid()
        // §14.4 Row 1's gauge is the only consumer of the 1-minute product, so
        // it is fetched here rather than on the refresh cycle (see KpNowcast).
        val latest = withContext(Dispatchers.Default) { runCatching { KpNowcast.fetchLatest() }.getOrNull() }
        estimatedKp = latest
        estimateFailed = latest == null
    }

    val auroraOccurrences = remember(occurrences) { occurrences.filter { it.phenomenon == Phenomenon.AURORA } }
    val slots = remember(auroraOccurrences, now) { forecastSlots(auroraOccurrences, now) }
    val issuedAt = auroraOccurrences.mapNotNull { (it.payload as? AuroraPayload)?.issuedAt }.maxOrNull()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Aurora", style = MaterialTheme.typography.headlineSmall)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (SWPC_SOURCE_ID in refreshing) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                TextButton(onClick = { state.refreshSources(setOf(SWPC_SOURCE_ID)) }) { Text("Refresh") }
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.width(240.dp)) { KpGaugeCard(estimatedKp, estimateFailed, state) }
            Box(Modifier.weight(1f)) { ForecastStripCard(slots, issuedAt, state) }
        }

        PolarViewCard(state, grid, locations)

        SectionCard("Your locations") {
            if (locations.isEmpty()) {
                Text("Add a location in Settings to get a verdict here.", style = MaterialTheme.typography.bodyMedium)
            }
            for (location in locations) {
                LocationVerdictRow(state, location, slots, estimatedKp, now)
            }
        }
    }
}

private const val SWPC_SOURCE_ID = "swpc"

/** §14.4 Row 1's gauge. */
@Composable
private fun KpGaugeCard(estimate: KpEstimate?, failed: Boolean, state: DesktopAppState) {
    SectionCard("Estimated Kp now") {
        if (estimate == null) {
            Text(
                if (failed) "Could not reach the 1-minute Kp product." else "Fetching…",
                style = MaterialTheme.typography.bodyMedium,
            )
            return@SectionCard
        }
        Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val center = Offset(size.width / 2f, size.height * 0.95f)
                val radius = minOf(size.width / 2f, size.height) * 0.85f
                // A 180° dial from Kp 0 (left) to Kp 9 (right).
                for (step in 0 until 9) {
                    val startAngle = 180f + step * 20f
                    drawArc(
                        color = kpColor(step + 0.5),
                        startAngle = startAngle,
                        sweepAngle = 18f,
                        useCenter = false,
                        topLeft = Offset(center.x - radius, center.y - radius),
                        size = Size(radius * 2, radius * 2),
                        style = Stroke(width = 14f),
                    )
                }
                val fraction = (estimate.estimatedKp / 9.0).coerceIn(0.0, 1.0)
                val angle = Math.toRadians(180.0 + fraction * 180.0)
                drawLine(
                    color = Color(0xFFF2F5FA),
                    start = center,
                    end = Offset(
                        center.x + (radius * 0.92 * cos(angle)).toFloat(),
                        center.y + (radius * 0.92 * sin(angle)).toFloat(),
                    ),
                    strokeWidth = 3f,
                )
                drawCircle(Color(0xFFF2F5FA), radius = 5f, center = center)
            }
        }
        Text(
            "Kp ${formatKp(estimate.estimatedKp)}" + (gScaleLabel(estimate.estimatedKp)?.let { " · $it" } ?: ""),
            style = MaterialTheme.typography.headlineSmall,
            color = kpColor(estimate.estimatedKp),
        )
        Text(
            "SWPC 1-minute estimate, ${formatDateTime(estimate.time, state.zone)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** One 3-hour bucket of §14.4's "24×3h bar strip". */
private data class ForecastSlot(val start: Instant, val kp: Double?)

/**
 * The next three days in 3-hour buckets. Buckets with no stored forecast are
 * left null on purpose: §7.3.3 only persists slots at or above the rules'
 * own Kp threshold, so an empty bucket means "below everything you asked
 * about", not "no data" — and the caption says so rather than drawing a
 * confident zero.
 */
private fun forecastSlots(auroraOccurrences: List<Occurrence>, now: Instant): List<ForecastSlot> {
    val byStart = auroraOccurrences
        .filter { (it.payload as? AuroraPayload)?.forecastKind == AuroraForecastKind.THREE_DAY }
        .associateBy({ it.window.start.epochSeconds / SLOT_SECONDS }) { (it.payload as AuroraPayload).kpForecast }
    val firstSlot = (now.epochSeconds / SLOT_SECONDS) * SLOT_SECONDS
    return (0 until SLOT_COUNT).map { index ->
        val start = Instant.fromEpochSeconds(firstSlot + index * SLOT_SECONDS)
        ForecastSlot(start, byStart[start.epochSeconds / SLOT_SECONDS])
    }
}

private const val SLOT_SECONDS = 3 * 3600L
private const val SLOT_COUNT = 24
private val EMPTY_SLOT_HEIGHT = 6.dp

@Composable
private fun ForecastStripCard(slots: List<ForecastSlot>, issuedAt: Instant?, state: DesktopAppState) {
    SectionCard("Three-day forecast") {
        Row(Modifier.fillMaxWidth().height(120.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom) {
            for (slot in slots) {
                val kp = slot.kp
                Column(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Bottom,
                ) {
                    gScaleLabel(kp ?: 0.0)?.let {
                        Text(it, style = MaterialTheme.typography.labelSmall, color = kpColor(kp ?: 0.0))
                    }
                    Box(
                        Modifier
                            .fillMaxWidth()
                            // A stored slot's bar is proportional to its Kp; a
                            // slot with no stored forecast still gets a visible
                            // stub, so the strip reads as "24 quiet slots"
                            // rather than as a card that failed to draw.
                            .height(if (kp == null) EMPTY_SLOT_HEIGHT else (kp / 9.0 * 80.0).dp.coerceAtLeast(3.dp))
                            .background(if (kp == null) Color(0xFF39445C) else kpColor(kp)),
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatTime(slots.first().start, state.zone), style = MaterialTheme.typography.labelSmall)
            Text(formatDateTime(slots.last().start, state.zone), style = MaterialTheme.typography.labelSmall)
        }
        Text(
            buildString {
                append(issuedAt?.let { "Based on the forecast issued ${formatDateTime(it, state.zone)}. " } ?: "No forecast stored yet. ")
                append("Empty bars are slots below every enabled rule's Kp threshold — those are not stored.")
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PolarViewCard(state: DesktopAppState, grid: OvationGrid?, locations: List<SavedLocation>) {
    var north by remember { mutableStateOf(true) }
    val raster = remember(grid, north) { grid?.let { AuroraPolarPlot.rasterize(it, north) } }

    SectionCard("OVATION nowcast") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = north, onClick = { north = true }, label = { Text("North") })
            FilterChip(selected = !north, onClick = { north = false }, label = { Text("South") })
        }
        if (grid == null || raster == null) {
            Text(
                "No nowcast grid yet — OVATION is only polled in the active tier (Kp of interest, or this dashboard open).",
                style = MaterialTheme.typography.bodyMedium,
            )
            return@SectionCard
        }

        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(360.dp)) {
                Canvas(Modifier.fillMaxSize()) {
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val radius = size.minDimension / 2f - 8f
                    drawCircle(POLAR_BACKGROUND, radius = radius, center = center)
                    drawPolarRaster(raster, center, radius)
                    drawPolarGraticule(center, radius)
                    drawLocationPins(locations, center, radius, north)
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ProbabilityColorbar()
                Text(
                    "Rim = ${AuroraPolarPlot.RIM_LATITUDE.toInt()}° latitude, rings at 60° and 75°; 0° longitude points up.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Observed ${formatDateTime(grid.observationTime, state.zone)} · forecast for ${formatDateTime(grid.forecastTime, state.zone)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun DrawScope.drawPolarRaster(raster: ImageBitmap, center: Offset, radius: Float) {
    drawImage(
        image = raster,
        dstOffset = IntOffset((center.x - radius).roundToInt(), (center.y - radius).roundToInt()),
        dstSize = IntSize((radius * 2).roundToInt(), (radius * 2).roundToInt()),
        filterQuality = FilterQuality.Low,
    )
}

/** Latitude rings at 60° and 75° plus the 45° rim, and a meridian spoke every 45°. */
private fun DrawScope.drawPolarGraticule(center: Offset, radius: Float) {
    for (latitude in listOf(45.0, 60.0, 75.0)) {
        val ringRadius = ((90.0 - latitude) / (90.0 - AuroraPolarPlot.RIM_LATITUDE)).toFloat() * radius
        drawCircle(GRATICULE.copy(alpha = 0.7f), radius = ringRadius, center = center, style = Stroke(width = 1f))
    }
    for (spoke in 0 until 8) {
        val angle = Math.toRadians(spoke * 45.0)
        drawLine(
            color = GRATICULE.copy(alpha = 0.4f),
            start = center,
            end = Offset(center.x + (radius * sin(angle)).toFloat(), center.y - (radius * cos(angle)).toFloat()),
            strokeWidth = 1f,
        )
    }
}

private fun DrawScope.drawLocationPins(locations: List<SavedLocation>, center: Offset, radius: Float, north: Boolean) {
    for (location in locations) {
        val position = AuroraPolarPlot.project(location.point, center, radius, north) ?: continue
        drawCircle(PIN_FILL, radius = 4f, center = position)
        drawCircle(PIN_OUTLINE, radius = 4f, center = position, style = Stroke(width = 1.5f))
    }
}

private val POLAR_BACKGROUND = Color(0xFF0D1522)
private val GRATICULE = Color(0xFF56637C)
private val PIN_FILL = Color(0xFFF2F5FA)
private val PIN_OUTLINE = Color(0xFF11151E)

@Composable
private fun ProbabilityColorbar() {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("Overhead probability", style = MaterialTheme.typography.labelSmall)
        Canvas(Modifier.width(200.dp).height(14.dp)) {
            val steps = 50
            val stepWidth = size.width / steps
            for (i in 0 until steps) {
                val probability = i * 100.0 / steps
                drawRect(
                    color = Color(AuroraPolarPlot.probabilityArgb(probability)),
                    topLeft = Offset(i * stepWidth, 0f),
                    size = Size(stepWidth + 1f, size.height),
                )
            }
        }
        Row(Modifier.width(200.dp), horizontalArrangement = Arrangement.SpaceBetween) {
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
private fun LocationVerdictRow(
    state: DesktopAppState,
    location: SavedLocation,
    slots: List<ForecastSlot>,
    estimate: KpEstimate?,
    now: Instant,
) {
    val geomagneticLat = remember(location) { geomagneticLatitudeDeg(location.point) }
    // §8.4 inverted: visible when |λgm| >= 66 - 2*Kp, so Kp_needed = (66 - |λgm|)/2.
    val kpNeeded = (66.0 - abs(geomagneticLat)) / 2.0
    val peakKp = slots.mapNotNull { it.kp }.maxOrNull()
    val darkness = remember(location, now) {
        // The astronomy search is a handful of iterations, but it is still
        // astronomy — keep it out of the draw path by remembering per tick.
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
                    margin != null && margin >= 0 -> "Now: Kp ${formatKp(current)} — ${formatDegrees(margin * 2, 1)} of margin. Look north after dark."
                    margin != null -> "Now: Kp ${formatKp(current)} — short by ${formatKp(abs(margin))} Kp."
                    // Reached only when there is no live reading but a forecast slot exists.
                    else -> "Forecast peak Kp ${formatKp(peakKp ?: 0.0)} over the next three days."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (margin != null && margin >= 0) kpColor(current) else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                darkness?.let { window ->
                    "Astronomical darkness tonight ${formatTime(window.start.toInstant(), state.zone)}–${formatTime(window.end.toInstant(), state.zone)}"
                } ?: "No astronomical darkness in the next two days — too far into the summer at this latitude.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
