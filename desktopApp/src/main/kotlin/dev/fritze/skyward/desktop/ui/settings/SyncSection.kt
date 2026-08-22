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
import dev.fritze.skyward.core.persistence.NotificationRepo
import dev.fritze.skyward.core.persistence.SyncImportResult
import dev.fritze.skyward.core.sync.ParsedSyncFile
import dev.fritze.skyward.core.sync.SyncCodec
import dev.fritze.skyward.core.sync.SyncFile
import dev.fritze.skyward.core.sync.SyncImportError
import dev.fritze.skyward.desktop.APP_VERSION
import dev.fritze.skyward.desktop.data.DesktopContainer
import dev.fritze.skyward.desktop.data.PrivateFiles
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
 * §12 export/import — "merge, never wipe" by default (§12.3), with an explicit
 * destructive "replace everything" path behind a confirmation. Both frontends
 * share the merge itself, so this screen and Android's `SyncViewModel` cannot
 * drift apart the way they once did (issue #50).
 */
@Composable
internal fun SyncSection(state: DesktopAppState) {
    var status by remember { mutableStateOf<String?>(null) }
    var pendingReplaceFile by remember { mutableStateOf<File?>(null) }
    // Import and export both write the DB; two of them at once would interleave
    // upserts and report each other's results. One at a time.
    var busy by remember { mutableStateOf(false) }

    SectionCard("Backup & sync") {
        Text(
            "Exports your locations, rules, settings and reminder history keys. Occurrence data is not exported — it is recomputed or refetched.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(
                enabled = !busy,
                onClick = {
                    val target = SyncFileDialogs.chooseExportTarget(defaultExportName())
                    if (target != null) {
                        busy = true
                        state.launch {
                            try {
                                status = runExport(state, target)
                            } finally {
                                busy = false
                            }
                        }
                    }
                },
            ) { Text("Export…") }

            TextButton(
                enabled = !busy,
                onClick = {
                    val source = SyncFileDialogs.chooseImportSource()
                    if (source != null) {
                        busy = true
                        state.launch {
                            try {
                                status = runImport(state.container, source, replaceEverything = false)
                            } finally {
                                busy = false
                            }
                        }
                    }
                },
            ) { Text("Import (merge)…") }

            TextButton(
                enabled = !busy,
                onClick = {
                    val source = SyncFileDialogs.chooseImportSource()
                    if (source != null) pendingReplaceFile = source
                },
            ) { Text("Import (replace everything)…") }
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
                    busy = true
                    state.launch {
                        try {
                            status = runImport(state.container, replaceFile, replaceEverything = true)
                        } finally {
                            busy = false
                        }
                    }
                }) { Text("Replace") }
            },
            dismissButton = { TextButton(onClick = { pendingReplaceFile = null }) { Text("Cancel") } },
        )
    }
}

private fun defaultExportName(): String = "skyward-export-${Clock.System.now().toString().take(10)}.json"

private suspend fun runExport(state: DesktopAppState, target: File): String = try {
    val container = state.container
    val now = Clock.System.now()
    // §10.4: prune here too, not just in ReplanCoordinator.replan -- an
    // export must never carry FIRED history older than 180 days even if
    // no replan has run yet this session.
    container.notificationRepo.pruneFiredBefore(now - NotificationRepo.FIRED_RETENTION)
    val file = SyncFile(
        exportedAt = now,
        appVersion = APP_VERSION,
        locations = container.locationRepo.getAll(),
        rules = container.ruleRepo.getAll(), // §9.1: hidden rules are included in sync
        settings = container.settingsRepo.observeAll().first(),
        firedNotificationIds = container.notificationRepo.getAll()
            .filter { it.status == NotificationStatus.FIRED }
            .map { it.id },
    )
    val text = SyncCodec.export(file)
    // Owner-only, like the database it came out of: an export is the same
    // precise coordinates in plain JSON, and the default umask would leave a
    // world-readable copy of them in whatever directory the user picked (P1).
    withContext(Dispatchers.IO) { PrivateFiles.writeText(target.toPath(), text) }
    "Exported ${file.locations.size} locations and ${file.rules.size} rules to ${target.name}."
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    "Export failed: ${e.message ?: e::class.simpleName}"
}

/**
 * §12.3's merge — or, if [replaceEverything], the explicit destructive
 * "replace everything" path. The merge itself lives in `:core`'s
 * [dev.fritze.skyward.core.persistence.SyncImportRepo] (P2/§4.1: frontends
 * never reimplement domain logic), which commits the replace-deletions and
 * every location/rule/settings/history write as one transaction (issue #13) —
 * a crash or cancellation partway through leaves neither the deletions nor a
 * half-imported database behind. All that stays here is file I/O and the
 * status strings.
 *
 * `internal`, not private, so the file-to-status-line path can be tested
 * without a Compose harness.
 */
internal suspend fun runImport(container: DesktopContainer, source: File, replaceEverything: Boolean): String = try {
    val text = withContext(Dispatchers.IO) { source.readText() }
    val parsed = SyncCodec.parseForImport(text)
    val result = container.syncImportRepo.applyImport(parsed, replaceEverything)

    // §12.3: "After import: full re-plan (§9.7)." The data above is already
    // committed, so a re-plan failure must not be reported as an import
    // failure — that would route through the generic "couldn't read that file"
    // message and could prompt a needless destructive retry.
    val replanFailed = runCatchingCancellable { container.replan() }.isFailure

    importSummary(result, parsed, replanFailed)
} catch (e: CancellationException) {
    throw e
} catch (e: SyncImportError) {
    "That file could not be imported: ${e.message}"
} catch (e: Exception) {
    "Import failed: ${e.message ?: e::class.simpleName}"
}

private fun importSummary(
    result: SyncImportResult,
    parsed: ParsedSyncFile,
    replanFailed: Boolean,
): String = buildString {
    append(
        "Imported ${result.locationsImported} locations, ${result.rulesImported} rules, " +
            "${result.settingsImported} settings, ${result.firedIdsImported} history entries.",
    )
    if (parsed.ruleWarnings.isNotEmpty()) {
        append(" ${parsed.ruleWarnings.size} rule(s) could not be fully read and were imported disabled: ")
        append(parsed.ruleWarnings.joinToString(", ") { it.ruleName })
        append(".")
    }
    if (replanFailed) append(" The import succeeded, but re-planning reminders failed — try a refresh.")
}
