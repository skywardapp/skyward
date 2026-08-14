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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.fritze.skyward.core.sync.SyncImportError
import dev.fritze.skyward.data.AppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private const val SYNC_FILE_MIME_TYPE = "application/json"

/**
 * §12.2/§12.3: SAF export/import round-trip -- explicitly deferred from M3
 * (#3's own accept criteria), ships here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncScreen(container: AppContainer, onBack: () -> Unit) {
    val viewModel: SyncViewModel = viewModel { SyncViewModel(container) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val controller = remember { SyncUiController(viewModel, scope, context.contentResolver) }
    var showReplaceConfirm by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(SYNC_FILE_MIME_TYPE)) { uri ->
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
                            "devices however you like -- email, a file manager, Syncthing. Importing merges by " +
                            "modification time and never deletes anything already on this device.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            Button(onClick = { exportLauncher.launch(suggestedExportFilename()) }, modifier = Modifier.fillMaxWidth()) { Text("Export to file") }
            OutlinedButton(onClick = { importLauncher.launch(arrayOf(SYNC_FILE_MIME_TYPE)) }, modifier = Modifier.fillMaxWidth()) { Text("Import from file") }
            TextButton(onClick = { showReplaceConfirm = true }) { Text("Replace everything instead…") }

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
                TextButton(onClick = { showReplaceConfirm = false; replaceLauncher.launch(arrayOf(SYNC_FILE_MIME_TYPE)) }) { Text("Pick file and replace") }
            },
            dismissButton = { TextButton(onClick = { showReplaceConfirm = false }) { Text("Cancel") } },
        )
    }
}

/** Owns the export/import mutable UI state and IO so [SyncScreen] itself stays declarative wiring. */
private class SyncUiController(
    private val viewModel: SyncViewModel,
    private val scope: CoroutineScope,
    private val contentResolver: ContentResolver,
) {
    var statusMessage by mutableStateOf<String?>(null); private set
    var errorMessage by mutableStateOf<String?>(null); private set
    var importSummary by mutableStateOf<ImportSummary?>(null); private set

    fun export(uri: Uri?, appVersion: String) {
        if (uri == null) return
        scope.launch {
            val text = viewModel.buildExportText(appVersion)
            val wrote = runCatching { contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) } }.isSuccess
            if (wrote) {
                statusMessage = "Exported your locations, rules, and settings."
                errorMessage = null
                importSummary = null
            } else {
                errorMessage = "Couldn't write the export file."
            }
        }
    }

    fun import(uri: Uri?, replaceEverything: Boolean) {
        if (uri == null) return
        val text = readText(uri)
        if (text == null) {
            errorMessage = "Couldn't read that file."
            return
        }
        scope.launch {
            runCatching { viewModel.applyImport(text, replaceEverything) }
                .onSuccess { summary -> importSummary = summary; errorMessage = null; statusMessage = null }
                .onFailure { errorMessage = importErrorMessage(it); importSummary = null; statusMessage = null }
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
        }
    }
}

/** §12.2: `skyward-settings-<yyyyMMdd-HHmm>.json`. */
private val EXPORT_FILENAME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")
private fun suggestedExportFilename(): String = "skyward-settings-${LocalDateTime.now().format(EXPORT_FILENAME_FORMAT)}.json"
