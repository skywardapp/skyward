package dev.fritze.skyward.ui.settings

import androidx.lifecycle.ViewModel
import dev.fritze.skyward.core.model.NotificationStatus
import dev.fritze.skyward.core.sync.RuleImportWarning
import dev.fritze.skyward.core.sync.SyncCodec
import dev.fritze.skyward.core.sync.SyncFile
import dev.fritze.skyward.core.sync.SyncMerge
import dev.fritze.skyward.data.AppContainer
import kotlinx.coroutines.flow.first
import kotlin.time.Clock

data class ImportSummary(
    val locationsImported: Int,
    val rulesImported: Int,
    val settingsImported: Int,
    val firedIdsImported: Int,
    val ruleWarnings: List<RuleImportWarning>,
)

/** §12: export/import (backs [SyncScreen]). */
class SyncViewModel(private val container: AppContainer) : ViewModel() {

    suspend fun buildExportText(appVersion: String): String {
        val file = SyncFile(
            exportedAt = Clock.System.now(),
            appVersion = appVersion,
            locations = container.locationRepo.getAll(),
            rules = container.ruleRepo.getAll(), // includes hidden rules -- §9.1: "included in evaluation & sync"
            settings = container.settingsRepo.observeAll().first(),
            firedNotificationIds = container.notificationRepo.getAll()
                .filter { it.status == NotificationStatus.FIRED }
                .map { it.id },
        )
        return SyncCodec.export(file)
    }

    /**
     * §12.3 merge (or, if [replaceEverything], the explicit destructive
     * "Replace everything" path: wipe local locations/rules first, so
     * everything from the file is then a "new id" and gets written). Throws
     * [dev.fritze.skyward.core.sync.SyncImportError] on a bad file.
     */
    suspend fun applyImport(text: String, replaceEverything: Boolean): ImportSummary {
        val parsed = SyncCodec.parseForImport(text)

        if (replaceEverything) {
            for (location in container.locationRepo.getAll()) container.locationRepo.delete(location.id)
            for (rule in container.ruleRepo.getAll()) container.ruleRepo.delete(rule.id)
        }

        val locationsToWrite = SyncMerge.newerOrMissing(container.locationRepo.getAll(), parsed.locations, { it.id }, { it.modifiedAt })
        for (location in locationsToWrite) container.locationRepo.upsert(location)

        val rulesToWrite = SyncMerge.newerOrMissing(container.ruleRepo.getAll(), parsed.rules, { it.id }, { it.modifiedAt })
        for (rule in rulesToWrite) container.ruleRepo.upsert(rule)

        for ((key, value) in parsed.settings) container.settingsRepo.set(key, value)

        var firedImported = 0
        for (id in parsed.firedNotificationIds) {
            if (container.notificationRepo.getById(id) == null) {
                container.notificationRepo.upsert(SyncMerge.syntheticFiredHistoryEntry(id, parsed.exportedAt))
                firedImported++
            }
        }

        container.replanAndSync() // §12.3: "After import: full re-plan (§9.7)."

        return ImportSummary(
            locationsImported = locationsToWrite.size,
            rulesImported = rulesToWrite.size,
            settingsImported = parsed.settings.size,
            firedIdsImported = firedImported,
            ruleWarnings = parsed.ruleWarnings,
        )
    }
}
