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

/**
 * §9.1's `NotifySchedule` minus its Instant fields, plus a UI-only
 * `quietHoursEnabled` (so toggling quiet hours off and back on doesn't lose
 * the previously-chosen from/to hours the way collapsing to `null` would).
 */
data class ScheduleDraft(
    val leads: Set<Duration>,
    val anchor: Anchor,
    val notifyOnFirstSeen: Boolean,
    val quietHoursEnabled: Boolean,
    val quietFromHour: Int,
    val quietToHour: Int,
)

/** §9.1's `NotifySchedule`: leads + anchor + notifyOnFirstSeen + quietHours. */
@Composable
fun ScheduleEditor(draft: ScheduleDraft, onChange: (ScheduleDraft) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Remind me before", style = MaterialTheme.typography.titleSmall)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (preset in LEAD_PRESETS) {
                FilterChip(
                    selected = preset in draft.leads,
                    onClick = { onChange(draft.copy(leads = if (preset in draft.leads) draft.leads - preset else draft.leads + preset)) },
                    label = { Text(formatLead(preset)) },
                )
            }
        }

        Text("Anchor time", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (option in Anchor.entries) {
                FilterChip(selected = draft.anchor == option, onClick = { onChange(draft.copy(anchor = option)) }, label = { Text(anchorLabel(option)) })
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
            Switch(checked = draft.notifyOnFirstSeen, onCheckedChange = { onChange(draft.copy(notifyOnFirstSeen = it)) })
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Quiet hours", style = MaterialTheme.typography.bodyMedium)
            Switch(checked = draft.quietHoursEnabled, onCheckedChange = { onChange(draft.copy(quietHoursEnabled = it)) })
        }
        if (draft.quietHoursEnabled) {
            LabeledIntSlider("From hour", draft.quietFromHour, 0..23) { onChange(draft.copy(quietFromHour = it)) }
            LabeledIntSlider("To hour", draft.quietToHour, 0..23) { onChange(draft.copy(quietToHour = it)) }
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
