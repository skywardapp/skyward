package dev.fritze.skyward.ui.rules

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.fritze.skyward.core.model.Phenomenon
import dev.fritze.skyward.core.rules.Anchor
import dev.fritze.skyward.core.rules.NotifySchedule
import dev.fritze.skyward.core.rules.Rule
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Instant

private val PREVIEW_TIMESTAMP = Instant.fromEpochSeconds(0)

/**
 * §13.4: "Live preview panel: 'matches N upcoming events' — run the engine on
 * the fly against current DB occurrences (debounce 500 ms). For COMPUTED
 * phenomena the preview additionally enumerates the past 2 years on demand
 * ... cache per session. Polled phenomena preview against current+future
 * data only, with the caption 'forecast-based — past matches can't be
 * shown'."
 */
@Composable
fun LivePreviewPanel(
    viewModel: RuleEditorViewModel,
    phenomena: Set<Phenomenon>,
    locationIds: List<String>?,
    conditionRoot: ConditionNode.Group,
) {
    val scope = rememberCoroutineScope()
    val enabledForPreview = phenomena.isNotEmpty() && conditionRoot.children.isNotEmpty()

    // A dedicated, stable Rule for evaluation only -- id/timestamps fixed so this only changes
    // (and the debounce below only re-fires) when something that actually affects matching does.
    val previewRule = remember(phenomena, locationIds, conditionRoot) {
        Rule(
            id = "preview", name = "", enabled = true, phenomena = phenomena, locationIds = locationIds,
            condition = conditionRoot.toCond(),
            schedule = NotifySchedule(emptyList(), Anchor.PEAK, notifyOnFirstSeen = false, quietHours = null),
            hidden = false, createdAt = PREVIEW_TIMESTAMP, modifiedAt = PREVIEW_TIMESTAMP,
        )
    }
    val isComputedPreviewable = remember(phenomena) { viewModel.hasComputedPhenomenon(phenomena) }

    var loading by remember { mutableStateOf(false) }
    var previewCount by remember { mutableStateOf<Int?>(null) }
    var pastCount by remember { mutableStateOf<Int?>(null) }
    var pastLoading by remember { mutableStateOf(false) }

    LaunchedEffect(previewRule, enabledForPreview) {
        pastCount = null // condition/phenomena changed -- a cached past count no longer applies
        if (!enabledForPreview) {
            previewCount = null
            return@LaunchedEffect
        }
        loading = true
        delay(500)
        previewCount = viewModel.previewCount(previewRule)
        loading = false
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Preview", style = MaterialTheme.typography.titleSmall)
            when {
                !enabledForPreview -> Text("Pick phenomena and at least one condition to preview matches.", style = MaterialTheme.typography.bodySmall)
                loading && previewCount == null -> CircularProgressIndicator(modifier = Modifier.size(20.dp))
                else -> {
                    val count = previewCount ?: 0
                    Text("Matches $count upcoming event${if (count == 1) "" else "s"}", style = MaterialTheme.typography.bodyMedium)
                    when {
                        !isComputedPreviewable -> Text("Forecast-based — past matches can't be shown.", style = MaterialTheme.typography.bodySmall)
                        pastCount != null -> Text("+$pastCount in the past 2 years", style = MaterialTheme.typography.bodySmall)
                        else -> TextButton(
                            onClick = {
                                scope.launch {
                                    pastLoading = true
                                    pastCount = viewModel.pastMatchCount(previewRule) ?: 0
                                    pastLoading = false
                                }
                            },
                            enabled = !pastLoading,
                        ) { Text(if (pastLoading) "Checking past 2 years…" else "Also check past 2 years") }
                    }
                }
            }
        }
    }
}
