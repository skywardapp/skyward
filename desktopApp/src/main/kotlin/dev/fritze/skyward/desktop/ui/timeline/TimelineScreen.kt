package dev.fritze.skyward.desktop.ui.timeline

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
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
import dev.fritze.skyward.desktop.ui.DesktopAppState
import dev.fritze.skyward.desktop.ui.common.rememberUpcoming
import dev.fritze.skyward.desktop.ui.eventdetail.EventDetailPane
import dev.fritze.skyward.desktop.ui.theme.phenomenonColor
import dev.fritze.skyward.desktop.ui.theme.qualityColor
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/** One drawn item: either a point marker (peak) or a segment (window), per §14.2. */
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
private val LANE_HEIGHT = 44.dp
private val AXIS_HEIGHT = 28.dp

/**
 * §14.2: one lane per phenomenon on a shared, near-term-expanded time axis;
 * markers colored by the best quality across saved locations; hover for a
 * summary, click for the detail pane.
 */
@Composable
fun TimelineScreen(state: DesktopAppState) {
    var filter by remember { mutableStateOf(UpcomingFilter(scope = UpcomingScope.ALL)) }
    val upcoming = rememberUpcoming(state, filter)
    val settings by state.settings.collectAsState()
    val now by state.tick.collectAsState()
    val selected = state.selectedOccurrenceId

    // Clamped to the same range Settings offers: a hand-edited or imported
    // setting of 0 would collapse the axis to zero width, and a wild one would
    // overflow the day arithmetic.
    val horizonYears = (settings["horizon_years"]?.toIntOrNull() ?: 3).coerceIn(1, 50)
    val end = remember(now, horizonYears) { now + (365L * horizonYears).days }

    // Lanes are fixed to the full phenomenon set rather than "whatever has
    // data": an empty Comets lane says "nothing predicted", which is
    // information; a missing lane just looks like a bug.
    val lanes = Phenomenon.entries

    Row(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                Text("Timeline", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "${formatDate(now, state.zone)} → ${formatDate(end, state.zone)} · the next 60 days take half the width",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            TimelineFilterChips(filter) { filter = it }

            Row(Modifier.fillMaxSize().padding(20.dp)) {
                LaneLabels(lanes)
                TimelineCanvas(
                    state = state,
                    lanes = lanes,
                    items = upcoming.items,
                    now = now,
                    end = end,
                    selectedOccurrenceId = selected,
                )
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

/** §14.2: "filter chips shared with Upcoming". */
@Composable
private fun TimelineFilterChips(filter: UpcomingFilter, onChange: (UpcomingFilter) -> Unit) {
    Row(
        Modifier.padding(horizontal = 20.dp).horizontalScroll(rememberScrollState()),
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
                    val next = if (phenomenon in filter.phenomena) filter.phenomena - phenomenon else filter.phenomena + phenomenon
                    onChange(filter.copy(phenomena = next))
                },
                label = { Text(phenomenonLabel(phenomenon)) },
            )
        }
    }
}

@Composable
private fun LaneLabels(lanes: List<Phenomenon>) {
    Column(Modifier.width(120.dp)) {
        Spacer(Modifier.height(AXIS_HEIGHT))
        for (phenomenon in lanes) {
            Row(
                modifier = Modifier.height(LANE_HEIGHT).fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(Modifier.width(6.dp).height(18.dp).clip(RoundedCornerShape(3.dp)).background(phenomenonColor(phenomenon)))
                Text(phenomenonLabel(phenomenon), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun TimelineCanvas(
    state: DesktopAppState,
    lanes: List<Phenomenon>,
    items: List<dev.fritze.skyward.core.planner.UpcomingItem>,
    now: Instant,
    end: Instant,
    selectedOccurrenceId: String?,
) {
    val density = LocalDensity.current
    var canvasSize by remember { mutableStateOf(Size.Zero) }
    var hovered by remember { mutableStateOf<TimelineItem?>(null) }
    var hoverPosition by remember { mutableStateOf(Offset.Zero) }

    val laneHeightPx = with(density) { LANE_HEIGHT.toPx() }
    val axisHeightPx = with(density) { AXIS_HEIGHT.toPx() }

    val scale = remember(now, end, canvasSize.width) {
        if (canvasSize.width <= 0f) null else TimelineScale(now, end, canvasSize.width)
    }
    val drawItems = remember(items, scale, lanes) {
        val currentScale = scale ?: return@remember emptyList()
        items.mapNotNull { item ->
            val laneIndex = lanes.indexOf(item.occurrence.phenomenon).takeIf { it >= 0 } ?: return@mapNotNull null
            if (item.occurrence.window.end < now || item.occurrence.window.start > end) return@mapNotNull null
            TimelineItem(
                occurrence = item.occurrence,
                quality = item.bestVisres.quality,
                bestLocationName = item.bestLocation.name,
                matchedRuleNames = item.matchedRuleNames,
                startX = currentScale.xOf(item.occurrence.window.start),
                endX = currentScale.xOf(item.occurrence.window.end),
                laneIndex = laneIndex,
            )
        }
    }
    val monthTicks = remember(scale) { scale?.let { monthTicks(it, state.zone) }.orEmpty() }

    // A filter change or a source refresh can remove whatever is hovered; the
    // pointer isn't moving, so nothing else would ever clear the tooltip.
    LaunchedEffect(drawItems) {
        hovered = hovered?.let { stale -> drawItems.firstOrNull { it.occurrence.id == stale.occurrence.id } }
    }

    Box(Modifier.fillMaxSize()) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                // Reading the size here rather than assigning it inside the draw
                // lambda: writing snapshot state during draw schedules another
                // frame from within a frame, which is a recomposition loop
                // waiting to happen.
                .onSizeChanged { canvasSize = it.toSize() }
                .timelinePointerHandling(
                    items = drawItems,
                    axisHeightPx = axisHeightPx,
                    laneHeightPx = laneHeightPx,
                    onHover = { item, position ->
                        hovered = item
                        if (position != null) hoverPosition = position
                    },
                    onSelect = { state.selectOccurrence(it.occurrence.id) },
                ),
        ) {
            drawTimeline(
                lanes = lanes.size,
                items = drawItems,
                monthTicks = monthTicks,
                axisHeightPx = axisHeightPx,
                laneHeightPx = laneHeightPx,
                nearTermBoundaryX = scale?.nearTermBoundaryX,
                selectedOccurrenceId = selectedOccurrenceId,
            )
        }

        // Axis labels and the tooltip are composables, not canvas text: they
        // need the app's typography and the tooltip needs a real card.
        for (tick in monthTicks) {
            if (!tick.labelled) continue
            Text(
                text = tick.label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.offset { IntOffset(tick.x.roundToInt() + 4, 2) },
            )
        }

        hovered?.let { item ->
            HoverCard(state, item, hoverPosition, canvasSize)
        }
    }
}

/** §14.2's "hover tooltip = card summary; click = detail", kept out of the canvas's own modifier chain. */
private fun Modifier.timelinePointerHandling(
    items: List<TimelineItem>,
    axisHeightPx: Float,
    laneHeightPx: Float,
    onHover: (TimelineItem?, Offset?) -> Unit,
    onSelect: (TimelineItem) -> Unit,
): Modifier = this
    .pointerInput(items, laneHeightPx) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent()
                when (event.type) {
                    PointerEventType.Move -> {
                        val position = event.changes.first().position
                        onHover(itemAt(position, items, axisHeightPx, laneHeightPx), position)
                    }
                    PointerEventType.Exit -> onHover(null, null)
                    else -> Unit
                }
            }
        }
    }
    .pointerInput(items, laneHeightPx) {
        detectTapGestures { position -> itemAt(position, items, axisHeightPx, laneHeightPx)?.let(onSelect) }
    }

@Composable
private fun HoverCard(state: DesktopAppState, item: TimelineItem, position: Offset, canvasSize: Size) {
    val now by state.tick.collectAsState()
    val cardWidth = 280.dp
    val density = LocalDensity.current
    val cardWidthPx = with(density) { cardWidth.toPx() }
    // Flip the card to the left of the cursor near the right edge so it never
    // hangs off the canvas.
    val x = if (position.x + cardWidthPx + 16 > canvasSize.width) position.x - cardWidthPx - 12 else position.x + 12

    Card(
        modifier = Modifier
            .width(cardWidth)
            .offset { IntOffset(x.roundToInt().coerceAtLeast(0), (position.y + 12).roundToInt()) },
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(item.occurrence.title, style = MaterialTheme.typography.titleSmall)
            val anchor = item.occurrence.peakTime ?: item.occurrence.window.start
            Text(
                "${formatDateTime(anchor, state.zone)} · ${formatRelative(now, anchor)}",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "${qualityLabel(item.quality)}${item.bestLocationName?.let { " at $it" }.orEmpty()}",
                style = MaterialTheme.typography.bodySmall,
                color = qualityColor(item.quality),
            )
            if (item.matchedRuleNames.isNotEmpty()) {
                Text(
                    item.matchedRuleNames.joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

private data class MonthTick(val x: Float, val label: String, val isYearStart: Boolean, val labelled: Boolean)

/**
 * §14.2's "month/year grid lines". Month labels are dropped where the
 * compressed far end would overprint them — a year label every January is
 * still readable there, and an unreadable axis is worse than a sparse one.
 */
private fun monthTicks(scale: TimelineScale, zone: kotlinx.datetime.TimeZone): List<MonthTick> {
    val ticks = mutableListOf<MonthTick>()
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

private fun DrawScope.drawMarkers(items: List<TimelineItem>, axisHeightPx: Float, laneHeightPx: Float, selectedOccurrenceId: String?) {
    for (item in items) {
        val centerY = axisHeightPx + item.laneIndex * laneHeightPx + laneHeightPx / 2f
        val color = qualityColor(item.quality)
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

private val GRID_COLOR = Color(0xFF2A3346)
private val LANE_BAND_COLOR = Color(0xFF161C29)
private val BOUNDARY_COLOR = Color(0xFF54617A)
private val TODAY_COLOR = Color(0xFFE9EEF7)

private fun itemAt(position: Offset, items: List<TimelineItem>, axisHeightPx: Float, laneHeightPx: Float): TimelineItem? {
    // `floor`, not `toInt()`: truncation rounds toward zero, so the whole axis
    // band above lane 0 (a negative fraction) would map into lane 0 and let a
    // hover over the month labels light up the first lane's markers.
    val lane = floor((position.y - axisHeightPx) / laneHeightPx).toInt()
    if (lane < 0) return null
    return items
        .filter { it.laneIndex == lane }
        .minByOrNull { item ->
            when {
                position.x in item.startX..item.endX -> 0f
                else -> minOf(abs(position.x - item.startX), abs(position.x - item.endX))
            }
        }
        ?.takeIf { item ->
            position.x in (item.startX - HOVER_SLOP)..(item.endX + HOVER_SLOP)
        }
}

private const val HOVER_SLOP = 8f
