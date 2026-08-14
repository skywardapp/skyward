package dev.fritze.skyward.desktop.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import dev.fritze.skyward.core.model.NotificationStatus
import dev.fritze.skyward.core.sync.SyncCodec
import dev.fritze.skyward.core.sync.SyncFile
import dev.fritze.skyward.core.sync.SyncImportError
import dev.fritze.skyward.core.sync.SyncMerge
import dev.fritze.skyward.desktop.APP_VERSION
import dev.fritze.skyward.desktop.ui.DesktopAppState
import dev.fritze.skyward.desktop.ui.common.SectionCard
import dev.fritze.skyward.desktop.ui.common.SyncFileDialogs
import dev.fritze.skyward.desktop.util.runCatchingCancellable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.time.Clock

/**
 * §12 export/import, same merge semantics as Android's `SyncViewModel` —
 * "merge, never wipe" by default (§12.3), with an explicit destructive
 * "replace everything" path behind a confirmation.
 */
@Composable
internal fun SyncSection(state: DesktopAppState) {
    var status by remember { mutableStateOf<String?>(null) }
    var pendingReplaceFile by remember { mutableStateOf<File?>(null) }

    SectionCard("Backup & sync") {
        Text(
            "Exports your locations, rules, settings and reminder history keys. Occurrence data is not exported — it is recomputed or refetched.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = {
                val target = SyncFileDialogs.chooseExportTarget(defaultExportName())
                if (target != null) {
                    state.launch {
                        status = runExport(state, target)
                    }
                }
            }) { Text("Export…") }

            TextButton(onClick = {
                val source = SyncFileDialogs.chooseImportSource()
                if (source != null) {
                    state.launch { status = runImport(state, source, replaceEverything = false) }
                }
            }) { Text("Import (merge)…") }

            TextButton(onClick = {
                val source = SyncFileDialogs.chooseImportSource()
                if (source != null) pendingReplaceFile = source
            }) { Text("Import (replace everything)…") }
        }
        status?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    }

    val replaceFile = pendingReplaceFile
    if (replaceFile != null) {
        AlertDialog(
            onDismissRequest = { pendingReplaceFile = null },
            title = { Text("Replace everything?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("This deletes all current locations and rules before importing ${replaceFile.name}.")
                    Text("Reminder history is kept. This cannot be undone.")
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingReplaceFile = null
                    state.launch { status = runImport(state, replaceFile, replaceEverything = true) }
                }) { Text("Replace") }
            },
            dismissButton = { TextButton(onClick = { pendingReplaceFile = null }) { Text("Cancel") } },
        )
    }
}

private fun defaultExportName(): String = "skyward-export-${Clock.System.now().toString().take(10)}.json"

private suspend fun runExport(state: DesktopAppState, target: File): String = try {
    val container = state.container
    val file = SyncFile(
        exportedAt = Clock.System.now(),
        appVersion = APP_VERSION,
        locations = container.locationRepo.getAll(),
        rules = container.ruleRepo.getAll(), // §9.1: hidden rules are included in sync
        settings = container.settingsRepo.observeAll().first(),
        firedNotificationIds = container.notificationRepo.getAll()
            .filter { it.status == NotificationStatus.FIRED }
            .map { it.id },
    )
    val text = SyncCodec.export(file)
    withContext(Dispatchers.IO) { target.writeText(text) }
    "Exported ${file.locations.size} locations and ${file.rules.size} rules to ${target.name}."
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    "Export failed: ${e.message ?: e::class.simpleName}"
}

/**
 * §12.3's merge, character for character the same as Android's — including
 * the degraded-rule guard (never let a rule this version couldn't decode
 * overwrite an intact local rule of the same id) and the "a failed re-plan is
 * not a failed import" split.
 */
private suspend fun runImport(state: DesktopAppState, source: File, replaceEverything: Boolean): String = try {
    val container = state.container
    val text = withContext(Dispatchers.IO) { source.readText() }
    val parsed = SyncCodec.parseForImport(text)

    if (replaceEverything) {
        for (location in container.locationRepo.getAll()) container.locationRepo.delete(location.id)
        for (rule in container.ruleRepo.getAll()) container.ruleRepo.delete(rule.id)
    }

    val locationsToWrite = SyncMerge.newerOrMissing(container.locationRepo.getAll(), parsed.locations, { it.id }, { it.modifiedAt })
    for (location in locationsToWrite) container.locationRepo.upsert(location)

    val localRules = container.ruleRepo.getAll()
    val localRuleIds = localRules.mapTo(mutableSetOf()) { it.id }
    val importableRules = parsed.rules.filterNot { it.id in parsed.degradedRuleIds && it.id in localRuleIds }
    val rulesToWrite = SyncMerge.newerOrMissing(localRules, importableRules, { it.id }, { it.modifiedAt })
    for (rule in rulesToWrite) container.ruleRepo.upsert(rule)

    for ((key, value) in parsed.settings) container.settingsRepo.set(key, value)

    var firedImported = 0
    for (id in parsed.firedNotificationIds) {
        if (container.notificationRepo.getById(id) == null) {
            container.notificationRepo.upsert(SyncMerge.syntheticFiredHistoryEntry(id, parsed.exportedAt))
            firedImported++
        }
    }

    val replanFailed = runCatchingCancellable { container.replan() }.isFailure
    buildString {
        append("Imported ${locationsToWrite.size} locations, ${rulesToWrite.size} rules, ${parsed.settings.size} settings, $firedImported history entries.")
        if (parsed.ruleWarnings.isNotEmpty()) {
            append(" ${parsed.ruleWarnings.size} rule(s) could not be fully read and were imported disabled: ")
            append(parsed.ruleWarnings.joinToString(", ") { it.ruleName })
            append(".")
        }
        if (replanFailed) append(" The import succeeded, but re-planning reminders failed — try a refresh.")
    }
} catch (e: CancellationException) {
    throw e
} catch (e: SyncImportError) {
    "That file could not be imported: ${e.message}"
} catch (e: Exception) {
    "Import failed: ${e.message ?: e::class.simpleName}"
}
