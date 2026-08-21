package dev.fritze.skyward.ui.settings

import androidx.lifecycle.ViewModel
import dev.fritze.skyward.core.model.NotificationStatus
import dev.fritze.skyward.core.persistence.NotificationRepo
import dev.fritze.skyward.core.persistence.SyncImportRepo
import dev.fritze.skyward.core.sync.RuleImportWarning
import dev.fritze.skyward.core.sync.SyncCodec
import dev.fritze.skyward.core.sync.SyncFile
import dev.fritze.skyward.data.AppContainer
import dev.fritze.skyward.util.runCatchingCancellable
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
        val now = Clock.System.now()
        // §10.4: prune here too, not just in ReplanCoordinator.replan -- an
        // export must never carry FIRED history older than 180 days even if
        // no replan has run yet this session.
        container.notificationRepo.pruneFiredBefore(now - NotificationRepo.FIRED_RETENTION)
        val file = SyncFile(
            exportedAt = now,
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
     *
     * All location/rule/settings/notification-history mutations commit as one database
     * transaction ([SyncImportRepo], issue #13): a failure or cancellation partway through
     * leaves neither replace-mode deletions nor any import write persisted.
     */
    suspend fun applyImport(text: String, replaceEverything: Boolean): ImportSummary {
        val parsed = SyncCodec.parseForImport(text)
        val result = container.syncImportRepo.applyImport(parsed, replaceEverything)

        // §12.3: "After import: full re-plan (§9.7)." The data above is already committed, so a
        // re-plan failure must not be reported as an import failure (that would route through the
        // generic "couldn't read that file" message and could prompt a needless destructive retry).
        val replanFailed = runCatchingCancellable { container.replanAndSync() }.isFailure

        return ImportSummary(
            locationsImported = result.locationsImported,
            rulesImported = result.rulesImported,
            settingsImported = result.settingsImported,
            firedIdsImported = result.firedIdsImported,
            ruleWarnings = parsed.ruleWarnings,
            replanFailed = replanFailed,
        )
    }
}
