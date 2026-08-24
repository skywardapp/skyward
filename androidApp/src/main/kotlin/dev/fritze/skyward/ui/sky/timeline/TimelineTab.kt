package dev.fritze.skyward.ui.sky.timeline

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.fritze.skyward.core.chart.TimelineScale
import dev.fritze.skyward.core.format.formatDate
import dev.fritze.skyward.core.format.formatDateTime
import dev.fritze.skyward.core.format.formatRelative
import dev.fritze.skyward.core.format.monthAbbreviation
import dev.fritze.skyward.core.format.phenomenonLabel
import dev.fritze.skyward.core.format.qualityLabel
import dev.fritze.skyward.core.model.Occurrence
import dev.fritze.skyward.core.model.Phenomenon
import dev.fritze.skyward.core.model.Quality
import dev.fritze.skyward.core.planner.UpcomingFilter
import dev.fritze.skyward.core.planner.UpcomingScope
import dev.fritze.skyward.data.AppContainer
import dev.fritze.skyward.ui.chart.ChartPalette
import dev.fritze.skyward.ui.sky.SkyUiState
import dev.fritze.skyward.ui.sky.rememberUpcomingItems
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.math.abs
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/**
 * §14.2's timeline on a phone.
 *
 * The axis, its piecewise near-term expansion and the month ticks are all
 * `:core`'s [TimelineScale] (ADR 0021); what differs from the desktop is
 * that a phone has no hover, so a tap opens a bottom sheet where the desktop
 * shows a card under the cursor, and the canvas scrolls horizontally at a
 * fixed width rather than fitting a wide pane.
 */
private data class TimelineItem(
    val occurrence: Occurrence,
    val quality: Quality,
    val bestLocationName: String?,
    val matchedRuleNames: List<String>,
    val startX: Float,
    val endX: Float,
    val laneIndex: Int,
) {
    val centerX: Float get() = (startX + endX) / 2f
    val isSegment: Boolean get() = endX - startX >= MIN_SEGMENT_WIDTH
}

private const val MIN_SEGMENT_WIDTH = 6f
private val LANE_HEIGHT = 40.dp
private val AXIS_HEIGHT = 24.dp
private val LANE_LABEL_WIDTH = 92.dp

/**
 * Wide enough that the compressed far end stays legible on a phone. The
 * desktop fits the axis to its pane; here the pane is 360 dp, which would
 * put three years into a thumb's width, so the canvas is given a fixed
 * width and scrolled instead.
 */
private val CANVAS_WIDTH = 900.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimelineTab(
    container: AppContainer,
    state: SkyUiState,
    zone: TimeZone,
    now: Instant,
    onOpenEvent: (String) -> Unit,
) {
    var filter by remember { mutableStateOf(UpcomingFilter(scope = UpcomingScope.ALL)) }
    val upcoming = rememberUpcomingItems(container, state, now, zone, filter)
    var selected by remember { mutableStateOf<TimelineItem?>(null) }

    // Lanes are fixed to the full phenomenon set rather than "whatever has
    // data": an empty Comets lane says "nothing predicted", which is
    // information; a missing lane just looks like a bug.
    val lanes = Phenomenon.entries
    val end = remember(now) { now + (365L * DEFAULT_HORIZON_YEARS).days }

    val density = LocalDensity.current
    val canvasWidthPx = with(density) { CANVAS_WIDTH.toPx() }
    val laneHeightPx = with(density) { LANE_HEIGHT.toPx() }
    val axisHeightPx = with(density) { AXIS_HEIGHT.toPx() }

    val scale = remember(now, end, canvasWidthPx) { TimelineScale(now, end, canvasWidthPx) }
    val drawItems = remember(upcoming.items, scale, lanes) {
        upcoming.items.mapNotNull { item ->
            val laneIndex = lanes.indexOf(item.occurrence.phenomenon).takeIf { it >= 0 } ?: return@mapNotNull null
            if (item.occurrence.window.end < now || item.occurrence.window.start > end) return@mapNotNull null
            TimelineItem(
                occurrence = item.occurrence,
                quality = item.bestVisres.quality,
                bestLocationName = item.bestLocation.name,
                matchedRuleNames = item.matchedRuleNames,
                startX = scale.xOf(item.occurrence.window.start),
                endX = scale.xOf(item.occurrence.window.end),
                laneIndex = laneIndex,
            )
        }
    }
    val monthTicks = remember(scale, zone) { monthTicks(scale, zone) }

    // A filter change or a source refresh can remove whatever is selected;
    // nothing else would ever clear the sheet.
    LaunchedEffect(drawItems) {
        selected = selected?.let { stale -> drawItems.firstOrNull { it.occurrence.id == stale.occurrence.id } }
    }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(
                "${formatDate(now, zone)} → ${formatDate(end, zone)}",
                style = MaterialTheme.typography.labelLarge,
            )
            Text(
                "The next 60 days take half the width · scroll sideways · tap an event",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        FilterChips(filter) { filter = it }

        Row(Modifier.fillMaxSize().padding(top = 8.dp)) {
            LaneLabels(lanes)
            Box(Modifier.horizontalScroll(rememberScrollState())) {
                Canvas(
                    modifier = Modifier
                        .width(CANVAS_WIDTH)
                        .height(AXIS_HEIGHT + LANE_HEIGHT * lanes.size)
                        // A canvas is one opaque node to a screen reader — it
                        // has no children to describe it. Naming it at least
                        // says what is on screen and how much of it; Upcoming
                        // remains the readable route to the events themselves.
                        .semantics {
                            contentDescription =
                                "Timeline of ${drawItems.size} events across ${lanes.size} phenomenon lanes"
                        }
                        .pointerInput(drawItems, laneHeightPx) {
                            detectTapGestures { position ->
                                selected = itemAt(position, drawItems, axisHeightPx, laneHeightPx)
                            }
                        },
                ) {
                    drawTimeline(
                        lanes = lanes.size,
                        items = drawItems,
                        monthTicks = monthTicks,
                        axisHeightPx = axisHeightPx,
                        laneHeightPx = laneHeightPx,
                        nearTermBoundaryX = scale.nearTermBoundaryX,
                        selectedOccurrenceId = selected?.occurrence?.id,
                    )
                }

                // Axis labels are composables, not canvas text: they need the
                // app's typography.
                for (tick in monthTicks) {
                    if (!tick.labelled) continue
                    Text(
                        text = tick.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = with(density) { tick.x.toDp() + 3.dp }, top = 2.dp),
                    )
                }
            }
        }
    }

    selected?.let { item ->
        ModalBottomSheet(onDismissRequest = { selected = null }) {
            DetailSheet(item, now, zone, onOpen = {
                selected = null
                onOpenEvent(item.occurrence.id)
            })
        }
    }
}

/** §14.2: "filter chips shared with Upcoming". */
@Composable
private fun FilterChips(filter: UpcomingFilter, onChange: (UpcomingFilter) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = filter.scope == UpcomingScope.MATCHED,
            onClick = {
                val next = if (filter.scope == UpcomingScope.MATCHED) UpcomingScope.ALL else UpcomingScope.MATCHED
                onChange(filter.copy(scope = next))
            },
            label = { Text("Matched only") },
        )
        for (phenomenon in Phenomenon.entries) {
            FilterChip(
                selected = phenomenon in filter.phenomena,
                onClick = {
                    val next = if (phenomenon in filter.phenomena) {
                        filter.phenomena - phenomenon
                    } else {
                        filter.phenomena + phenomenon
                    }
                    onChange(filter.copy(phenomena = next))
                },
                label = { Text(phenomenonLabel(phenomenon)) },
            )
        }
    }
}

@Composable
private fun LaneLabels(lanes: List<Phenomenon>) {
    Column(Modifier.width(LANE_LABEL_WIDTH).padding(top = AXIS_HEIGHT)) {
        for (phenomenon in lanes) {
            Row(
                Modifier.height(LANE_HEIGHT).padding(start = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .width(5.dp)
                        .height(16.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(ChartPalette.phenomenon(phenomenon)),
                )
                Text(
                    phenomenonLabel(phenomenon),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 2,
                )
            }
        }
    }
}

@Composable
private fun DetailSheet(item: TimelineItem, now: Instant, zone: TimeZone, onOpen: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(item.occurrence.title, style = MaterialTheme.typography.titleMedium)
        val anchor = item.occurrence.peakTime ?: item.occurrence.window.start
        Text(
            "${formatDateTime(anchor, zone)} · ${formatRelative(now, anchor)}",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            "${qualityLabel(item.quality)}${item.bestLocationName?.let { " at $it" }.orEmpty()}",
            style = MaterialTheme.typography.bodyMedium,
            color = ChartPalette.quality(item.quality),
        )
        if (item.matchedRuleNames.isNotEmpty()) {
            Text(
                item.matchedRuleNames.joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        TextButton(onClick = onOpen) { Text("Open event") }
    }
}

private data class MonthTick(val x: Float, val label: String, val isYearStart: Boolean, val labelled: Boolean)

/**
 * §14.2's "month/year grid lines". Month labels are dropped where the
 * compressed far end would overprint them — a year label every January is
 * still readable there, and an unreadable axis is worse than a sparse one.
 */
private fun monthTicks(scale: TimelineScale, zone: TimeZone): List<MonthTick> {
    val ticks = mutableListOf<MonthTick>()
    // `.month.number` / `.day` are the non-deprecated kotlinx-datetime
    // spellings but don't resolve against this project's version — see the
    // note in core/format/Presentation.kt. Keep these in step with it.
    var date = scale.now.toLocalDateTime(zone).date.let { LocalDate(it.year, it.monthNumber, 1) }.plus(1, DateTimeUnit.MONTH)
    var lastLabelledX = Float.NEGATIVE_INFINITY
    while (true) {
        val instant = LocalDateTime(date, LocalTime(0, 0)).toInstant(zone)
        if (instant > scale.end) break
        val x = scale.xOf(instant)
        val isYearStart = date.monthNumber == 1
        val labelled = isYearStart || (x - lastLabelledX) >= MIN_LABEL_SPACING
        if (labelled) lastLabelledX = x
        ticks += MonthTick(
            x = x,
            label = if (isYearStart) date.year.toString() else monthAbbreviation(date.monthNumber),
            isYearStart = isYearStart,
            labelled = labelled,
        )
        date = date.plus(1, DateTimeUnit.MONTH)
    }
    return ticks
}

private const val MIN_LABEL_SPACING = 46f
private const val DEFAULT_HORIZON_YEARS = 3

/** Nearest item in the tapped lane, within a fingertip's reach of the tap. */
private fun itemAt(
    position: Offset,
    items: List<TimelineItem>,
    axisHeightPx: Float,
    laneHeightPx: Float,
): TimelineItem? {
    val lane = ((position.y - axisHeightPx) / laneHeightPx).toInt()
    return items
        .filter { it.laneIndex == lane }
        .minByOrNull { item ->
            when {
                item.isSegment && position.x in item.startX..item.endX -> 0f
                else -> abs(item.centerX - position.x)
            }
        }
        ?.takeIf { item ->
            (item.isSegment && position.x in item.startX..item.endX) ||
                abs(item.centerX - position.x) <= TAP_RADIUS
        }
}

private const val TAP_RADIUS = 24f

private fun DrawScope.drawTimeline(
    lanes: Int,
    items: List<TimelineItem>,
    monthTicks: List<MonthTick>,
    axisHeightPx: Float,
    laneHeightPx: Float,
    nearTermBoundaryX: Float?,
    selectedOccurrenceId: String?,
) {
    val lanesHeight = lanes * laneHeightPx
    drawLaneBands(lanes, axisHeightPx, laneHeightPx)
    drawMonthGrid(monthTicks, axisHeightPx, lanesHeight)
    // The gradient change is real information about the axis; showing it beats
    // leaving the reader to wonder why January is wider than June.
    nearTermBoundaryX?.let { boundary ->
        drawLine(BOUNDARY_COLOR, Offset(boundary, axisHeightPx), Offset(boundary, axisHeightPx + lanesHeight), strokeWidth = 1f)
    }
    drawMarkers(items, axisHeightPx, laneHeightPx, selectedOccurrenceId)
    // §14.2's "today" cursor — always at x = 0, since the axis starts at now.
    drawLine(TODAY_COLOR, Offset(0f, axisHeightPx - 4f), Offset(0f, axisHeightPx + lanesHeight), strokeWidth = 2f)
}

private fun DrawScope.drawLaneBands(lanes: Int, axisHeightPx: Float, laneHeightPx: Float) {
    for (lane in 1 until lanes step 2) {
        drawRect(
            color = LANE_BAND_COLOR,
            topLeft = Offset(0f, axisHeightPx + lane * laneHeightPx),
            size = Size(size.width, laneHeightPx),
        )
    }
}

private fun DrawScope.drawMonthGrid(monthTicks: List<MonthTick>, axisHeightPx: Float, lanesHeight: Float) {
    for (tick in monthTicks) {
        drawLine(
            color = if (tick.isYearStart) GRID_COLOR.copy(alpha = 0.95f) else GRID_COLOR.copy(alpha = 0.45f),
            start = Offset(tick.x, axisHeightPx),
            end = Offset(tick.x, axisHeightPx + lanesHeight),
            strokeWidth = if (tick.isYearStart) 1.5f else 1f,
        )
    }
}

/**
 * Fixed quality hues rather than [ChartPalette]'s themed pair: the canvas
 * paints its own lane bands and grid, so its marks sit on a dark ground in
 * either theme and the light ramp would draw dark on dark.
 */
private fun DrawScope.drawMarkers(
    items: List<TimelineItem>,
    axisHeightPx: Float,
    laneHeightPx: Float,
    selectedOccurrenceId: String?,
) {
    for (item in items) {
        val centerY = axisHeightPx + item.laneIndex * laneHeightPx + laneHeightPx / 2f
        val color = darkQuality(item.quality)
        val selected = item.occurrence.id == selectedOccurrenceId
        if (item.isSegment) {
            drawRoundRect(
                color = color.copy(alpha = if (selected) 1f else 0.7f),
                topLeft = Offset(item.startX, centerY - 6f),
                size = Size(item.endX - item.startX, 12f),
                cornerRadius = CornerRadius(6f, 6f),
            )
        } else {
            drawCircle(color.copy(alpha = if (selected) 1f else 0.85f), radius = 6f, center = Offset(item.centerX, centerY))
        }
        if (selected) {
            drawCircle(Color.White, radius = 10f, center = Offset(item.centerX, centerY), style = Stroke(width = 2f))
        }
    }
}

private fun darkQuality(quality: Quality): Color = when (quality) {
    Quality.NONE -> Color(0xFF5A6377)
    Quality.MARGINAL -> Color(0xFFD8A657)
    Quality.GOOD -> Color(0xFF6FB2E8)
    Quality.EXCELLENT -> Color(0xFF6FE3A8)
}

private val GRID_COLOR = Color(0xFF2A3346)
private val LANE_BAND_COLOR = Color(0xFF161C29)
private val BOUNDARY_COLOR = Color(0xFF54617A)
private val TODAY_COLOR = Color(0xFFE7ECF6)
