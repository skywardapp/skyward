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
import kotlinx.coroutines.CoroutineScope
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
    val previewRule = remember(phenomena, locationIds, conditionRoot) { buildPreviewRule(phenomena, locationIds, conditionRoot) }
    val isComputedPreviewable = remember(phenomena) { viewModel.hasComputedPhenomenon(phenomena) }
    val state = remember { PreviewState() }

    LaunchedEffect(previewRule, enabledForPreview) { state.refresh(viewModel, previewRule, enabledForPreview) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Preview", style = MaterialTheme.typography.titleSmall)
            PreviewBody(enabledForPreview, isComputedPreviewable, state) { state.loadPastCount(scope, viewModel, previewRule) }
        }
    }
}

private fun buildPreviewRule(phenomena: Set<Phenomenon>, locationIds: List<String>?, conditionRoot: ConditionNode.Group): Rule = Rule(
    id = "preview", name = "", enabled = true, phenomena = phenomena, locationIds = locationIds,
    condition = conditionRoot.toCond(),
    schedule = NotifySchedule(emptyList(), Anchor.PEAK, notifyOnFirstSeen = false, quietHours = null),
    hidden = false, createdAt = PREVIEW_TIMESTAMP, modifiedAt = PREVIEW_TIMESTAMP,
)

/** The preview's own mutable state, kept separate so [LivePreviewPanel] itself stays plain wiring. */
private class PreviewState {
    var loading by mutableStateOf(false); private set
    var previewCount by mutableStateOf<Int?>(null); private set
    var pastCount by mutableStateOf<Int?>(null); private set
    var pastLoading by mutableStateOf(false); private set

    suspend fun refresh(viewModel: RuleEditorViewModel, rule: Rule, enabled: Boolean) {
        pastCount = null // condition/phenomena changed -- a cached past count no longer applies
        if (!enabled) {
            previewCount = null
            return
        }
        loading = true
        delay(500)
        previewCount = viewModel.previewCount(rule)
        loading = false
    }

    fun loadPastCount(scope: CoroutineScope, viewModel: RuleEditorViewModel, rule: Rule) {
        scope.launch {
            pastLoading = true
            pastCount = viewModel.pastMatchCount(rule) ?: 0
            pastLoading = false
        }
    }
}

@Composable
private fun PreviewBody(enabledForPreview: Boolean, isComputedPreviewable: Boolean, state: PreviewState, onCheckPast: () -> Unit) {
    if (!enabledForPreview) {
        Text("Pick phenomena and at least one condition to preview matches.", style = MaterialTheme.typography.bodySmall)
        return
    }
    if (state.loading && state.previewCount == null) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp))
        return
    }
    val count = state.previewCount ?: 0
    Text("Matches $count upcoming event${if (count == 1) "" else "s"}", style = MaterialTheme.typography.bodyMedium)
    PastMatchesRow(isComputedPreviewable, state.pastCount, state.pastLoading, onCheckPast)
}

@Composable
private fun PastMatchesRow(isComputedPreviewable: Boolean, pastCount: Int?, pastLoading: Boolean, onCheckPast: () -> Unit) {
    if (!isComputedPreviewable) {
        Text("Forecast-based — past matches can't be shown.", style = MaterialTheme.typography.bodySmall)
        return
    }
    if (pastCount != null) {
        Text("+$pastCount in the past 2 years", style = MaterialTheme.typography.bodySmall)
        return
    }
    TextButton(onClick = onCheckPast, enabled = !pastLoading) {
        Text(if (pastLoading) "Checking past 2 years…" else "Also check past 2 years")
    }
}
