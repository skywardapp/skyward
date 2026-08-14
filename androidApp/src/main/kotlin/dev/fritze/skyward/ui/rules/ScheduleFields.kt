package dev.fritze.skyward.ui.rules

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.fritze.skyward.core.rules.Anchor
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

val LEAD_PRESETS: List<Duration> = listOf(2.hours, 6.hours, 12.hours, 1.days, 3.days, 7.days, 30.days, 180.days)

fun formatLead(duration: Duration): String = when {
    duration.inWholeDays >= 30 -> "${duration.inWholeDays / 30} month${if (duration.inWholeDays / 30 == 1L) "" else "s"} before"
    duration.inWholeDays >= 1 -> "${duration.inWholeDays} day${if (duration.inWholeDays == 1L) "" else "s"} before"
    else -> "${duration.inWholeHours} hour${if (duration.inWholeHours == 1L) "" else "s"} before"
}

private fun anchorLabel(anchor: Anchor): String = when (anchor) {
    Anchor.PEAK -> "Peak"
    Anchor.WINDOW_START -> "Window start"
    Anchor.BEST_VIEWING -> "Best viewing"
}

/** §9.1's `NotifySchedule`: leads + anchor + notifyOnFirstSeen + quietHours. */
@Composable
fun ScheduleEditor(
    leads: Set<Duration>,
    onLeadsChange: (Set<Duration>) -> Unit,
    anchor: Anchor,
    onAnchorChange: (Anchor) -> Unit,
    notifyOnFirstSeen: Boolean,
    onNotifyOnFirstSeenChange: (Boolean) -> Unit,
    quietHoursEnabled: Boolean,
    onQuietHoursEnabledChange: (Boolean) -> Unit,
    quietFromHour: Int,
    quietToHour: Int,
    onQuietHoursChange: (Int, Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Remind me before", style = MaterialTheme.typography.titleSmall)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (preset in LEAD_PRESETS) {
                FilterChip(
                    selected = preset in leads,
                    onClick = { onLeadsChange(if (preset in leads) leads - preset else leads + preset) },
                    label = { Text(formatLead(preset)) },
                )
            }
        }

        Text("Anchor time", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (option in Anchor.entries) {
                FilterChip(selected = anchor == option, onClick = { onAnchorChange(option) }, label = { Text(anchorLabel(option)) })
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Notify as soon as matched", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Fires immediately the first time an event matches, instead of waiting for a lead time above (aurora, comets, Earth events).",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(checked = notifyOnFirstSeen, onCheckedChange = onNotifyOnFirstSeenChange)
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Quiet hours", style = MaterialTheme.typography.bodyMedium)
            Switch(checked = quietHoursEnabled, onCheckedChange = onQuietHoursEnabledChange)
        }
        if (quietHoursEnabled) {
            LabeledIntSlider("From hour", quietFromHour, 0..23) { onQuietHoursChange(it, quietToHour) }
            LabeledIntSlider("To hour", quietToHour, 0..23) { onQuietHoursChange(quietFromHour, it) }
        }
    }
}

@Composable
fun WeekendCheckbox(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Text("Include Friday night")
    }
}
