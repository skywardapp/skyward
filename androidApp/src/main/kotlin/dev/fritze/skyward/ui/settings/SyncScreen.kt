package dev.fritze.skyward.ui.settings

import android.content.ContentResolver
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.fritze.skyward.core.sync.SyncImportError
import dev.fritze.skyward.data.AppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private const val EXPORT_MIME = "application/json"

// Many document providers report a `.json` file as application/octet-stream or text/plain,
// especially after transfer by email or a file-sync tool -- exactly the transports the "What
// syncs" copy below recommends. Filtering the picker by EXPORT_MIME would grey those out; instead
// accept anything and let SyncCodec.parseForImport reject non-Skyward files (SyncImportError.WrongFormat).
private val IMPORT_MIME_FILTER = arrayOf("*/*")

/**
 * §12.2/§12.3: SAF export/import round-trip -- explicitly deferred from M3
 * (#3's own accept criteria), ships here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncScreen(container: AppContainer, onBack: () -> Unit) {
    val viewModel: SyncViewModel = viewModel { SyncViewModel(container) }
    val context = LocalContext.current
    // container.applicationScope, not viewModelScope: this screen's ViewModel is scoped to its
    // Navigation-Compose back-stack entry, which is cleared -- cancelling viewModelScope -- the
    // moment onBack() pops it, i.e. exactly when a destructive "replace everything" import is
    // most likely to be mid-flight. applicationScope is process-lifetime, so it survives that.
    val controller = remember { SyncUiController(viewModel, container.applicationScope, context.contentResolver) }
    var showReplaceConfirm by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(EXPORT_MIME)) { uri ->
        val appVersion = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"
        controller.export(uri, appVersion)
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> controller.import(uri, replaceEverything = false) }
    val replaceLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> controller.import(uri, replaceEverything = true) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sync") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("What syncs", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Locations, rules, and settings export to a single JSON file you can move between " +
                            "devices however you like -- email, a file manager, Syncthing. Importing keeps " +
                            "locations and rules that are newer on this device, and never deletes them. " +
                            "Settings in the file always replace the settings on this device.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            Button(
                onClick = { exportLauncher.launch(suggestedExportFilename()) },
                enabled = !controller.isBusy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Export to file") }
            OutlinedButton(
                onClick = { importLauncher.launch(IMPORT_MIME_FILTER) },
                enabled = !controller.isBusy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Import from file") }
            TextButton(onClick = { showReplaceConfirm = true }, enabled = !controller.isBusy) { Text("Replace everything instead…") }

            controller.statusMessage?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            controller.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            controller.importSummary?.let { ImportSummaryCard(it) }
        }
    }

    if (showReplaceConfirm) {
        AlertDialog(
            onDismissRequest = { showReplaceConfirm = false },
            title = { Text("Replace everything?") },
            text = {
                Text(
                    "This deletes every location and rule currently on this device, then imports the file you " +
                        "pick from scratch. This can't be undone.",
                )
            },
            confirmButton = {
                TextButton(onClick = { showReplaceConfirm = false; replaceLauncher.launch(IMPORT_MIME_FILTER) }) { Text("Pick file and replace") }
            },
            dismissButton = { TextButton(onClick = { showReplaceConfirm = false }) { Text("Cancel") } },
        )
    }
}

/**
 * Owns the export/import mutable UI state and IO so [SyncScreen] itself
 * stays declarative wiring. Launches on the process-lifetime [scope]
 * (`container.applicationScope`) rather than a composition- or
 * ViewModel-scoped coroutine, so navigating back mid-import can't cancel it
 * after `applyImport`'s destructive "replace everything" deletes have
 * already run; [isBusy] rejects a second call while one is in flight, so a
 * double-tap can't interleave two imports.
 */
private class SyncUiController(
    private val viewModel: SyncViewModel,
    private val scope: CoroutineScope,
    private val contentResolver: ContentResolver,
) {
    var statusMessage by mutableStateOf<String?>(null); private set
    var errorMessage by mutableStateOf<String?>(null); private set
    var importSummary by mutableStateOf<ImportSummary?>(null); private set
    var isBusy by mutableStateOf(false); private set

    fun export(uri: Uri?, appVersion: String) {
        if (uri == null || isBusy) return
        isBusy = true
        scope.launch {
            val text = viewModel.buildExportText(appVersion)
            val wrote = runCatching { withContext(Dispatchers.IO) { contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) } } }.isSuccess
            if (wrote) {
                statusMessage = "Exported your locations, rules, and settings."
                errorMessage = null
                importSummary = null
            } else {
                errorMessage = "Couldn't write the export file."
            }
            isBusy = false
        }
    }

    fun import(uri: Uri?, replaceEverything: Boolean) {
        if (uri == null || isBusy) return
        isBusy = true
        scope.launch {
            val text = withContext(Dispatchers.IO) { readText(uri) }
            if (text == null) {
                errorMessage = "Couldn't read that file."
                isBusy = false
                return@launch
            }
            runCatching { viewModel.applyImport(text, replaceEverything) }
                .onSuccess { summary -> importSummary = summary; errorMessage = null; statusMessage = null }
                .onFailure { errorMessage = importErrorMessage(it); importSummary = null; statusMessage = null }
            isBusy = false
        }
    }

    private fun readText(uri: Uri): String? =
        runCatching { contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } }.getOrNull()
}

private fun importErrorMessage(t: Throwable): String = when (t) {
    is SyncImportError.WrongFormat -> "That file isn't a Skyward sync file."
    is SyncImportError.UnknownFormatVersion -> "That file was made by a version of Skyward this app doesn't understand."
    is SyncImportError.Malformed -> "That file looks corrupted (${t.detail})."
    else -> "Couldn't read that file (${t.message})."
}

@Composable
private fun ImportSummaryCard(summary: ImportSummary) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Import complete", style = MaterialTheme.typography.titleSmall)
            Text(
                "${summary.locationsImported} location(s) and ${summary.rulesImported} rule(s) updated, " +
                    "${summary.settingsImported} setting(s) applied, ${summary.firedIdsImported} past notification(s) recorded.",
                style = MaterialTheme.typography.bodySmall,
            )
            if (summary.ruleWarnings.isNotEmpty()) {
                Text(
                    "${summary.ruleWarnings.size} rule(s) use a condition this app version doesn't recognize " +
                        "and were imported disabled: " + summary.ruleWarnings.joinToString(", ") { it.ruleName },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (summary.replanFailed) {
                Text(
                    "Your data imported, but reminders couldn't be recomputed just now. Reopening the app will retry.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/** §12.2: `skyward-settings-<yyyyMMdd-HHmm>.json`. */
private val EXPORT_FILENAME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")
private fun suggestedExportFilename(): String = "skyward-settings-${LocalDateTime.now().format(EXPORT_FILENAME_FORMAT)}.json"
