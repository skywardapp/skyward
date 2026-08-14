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
    /** True if data imported successfully but the post-import re-plan (§9.7) failed -- the import itself still committed. */
    val replanFailed: Boolean = false,
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

        val localRules = container.ruleRepo.getAll()
        val localRuleIds = localRules.mapTo(mutableSetOf()) { it.id }
        // A rule this app version couldn't fully decode was reconstructed with an inert placeholder
        // condition but keeps the original `modifiedAt` (SyncCodec.ParsedSyncFile.degradedRuleIds) --
        // without this filter, a newer `modifiedAt` alone would let it win the merge below and
        // silently destroy an intact local rule sharing its id. Never import a degraded rule over
        // one that already exists locally; still take it if there's no local copy at all.
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

        // §12.3: "After import: full re-plan (§9.7)." The data above is already committed, so a
        // re-plan failure must not be reported as an import failure (that would route through the
        // generic "couldn't read that file" message and could prompt a needless destructive retry).
        val replanFailed = runCatching { container.replanAndSync() }.isFailure

        return ImportSummary(
            locationsImported = locationsToWrite.size,
            rulesImported = rulesToWrite.size,
            settingsImported = parsed.settings.size,
            firedIdsImported = firedImported,
            ruleWarnings = parsed.ruleWarnings,
            replanFailed = replanFailed,
        )
    }
}
