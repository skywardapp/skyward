package dev.fritze.skyward.core.persistence

import dev.fritze.skyward.core.rules.Cond
import dev.fritze.skyward.core.rules.NotifySchedule
import dev.fritze.skyward.core.sync.ParsedSyncFile
import dev.fritze.skyward.core.sync.SyncMerge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString

/** Write counts for a completed import -- backs [dev.fritze.skyward.ui.settings.ImportSummary]. */
data class SyncImportResult(
    val locationsImported: Int,
    val rulesImported: Int,
    val settingsImported: Int,
    val firedIdsImported: Int,
)

/**
 * §12.3 import, made atomic (issue #13): every location/rule replace-deletion, location/rule/
 * settings write, and synthetic fired-notification-history write for one `applyImport` call runs
 * inside a single SQLDelight transaction, so a failure or cancellation partway through leaves
 * neither the replace-mode deletions nor any import write committed.
 *
 * Deliberately does not call [LocationRepo]/[RuleRepo]/[SettingsRepo]/[NotificationRepo]'s own
 * suspend methods from inside the transaction body: those each dispatch internally via
 * `withContext(Dispatchers.Default)`, which can hop off the thread the transaction is bound to
 * and invalidate the transaction scope on some SQLDelight drivers. Instead this mirrors
 * [LocationRepo.upsert] -- the codebase's one existing transaction -- by issuing raw,
 * non-suspend `db.xxxQueries` calls directly inside `db.transactionWithResult { }`, with the
 * whole block dispatched once via `withContext(Dispatchers.Default)`.
 *
 * The merge decision (which incoming locations/rules are newer than what's on-device) is also
 * read from inside the transaction, not from a snapshot taken beforehand, so a concurrent write
 * from elsewhere can't be merged against stale data.
 */
class SyncImportRepo(private val db: SkywardDatabase) {

    suspend fun applyImport(parsed: ParsedSyncFile, replaceEverything: Boolean): SyncImportResult =
        withContext(Dispatchers.Default) {
            db.transactionWithResult {
                if (replaceEverything) {
                    for (location in db.savedLocationQueries.selectAll().executeAsList()) db.savedLocationQueries.deleteById(location.id)
                    for (rule in db.ruleQueries.selectAll().executeAsList()) db.ruleQueries.deleteById(rule.id)
                }

                val localLocations = db.savedLocationQueries.selectAll().executeAsList().map { it.toModel() }
                val locationsToWrite = SyncMerge.newerOrMissing(localLocations, parsed.locations, { it.id }, { it.modifiedAt })
                for (location in locationsToWrite) {
                    if (location.isPrimary) db.savedLocationQueries.clearPrimary(location.id)
                    db.savedLocationQueries.upsert(
                        id = location.id,
                        name = location.name,
                        lat_deg = location.point.latDeg,
                        lon_deg = location.point.lonDeg,
                        is_primary = if (location.isPrimary) 1L else 0L,
                        created_at = location.createdAt.toString(),
                        modified_at = location.modifiedAt.toString(),
                    )
                }

                val localRules = db.ruleQueries.selectAll().executeAsList().map { it.toModel() }
                val localRuleIds = localRules.mapTo(mutableSetOf()) { it.id }
                // See SyncViewModel's former inline comment (issue #13): never let a degraded rule
                // (placeholder condition after a failed decode) overwrite an intact local rule
                // sharing its id, even if its modifiedAt would otherwise win the merge.
                val importableRules = parsed.rules.filterNot { it.id in parsed.degradedRuleIds && it.id in localRuleIds }
                val rulesToWrite = SyncMerge.newerOrMissing(localRules, importableRules, { it.id }, { it.modifiedAt })
                for (rule in rulesToWrite) {
                    db.ruleQueries.upsert(
                        id = rule.id,
                        name = rule.name,
                        enabled = if (rule.enabled) 1L else 0L,
                        phenomena_json = persistenceJson.encodeToString(rule.phenomena),
                        location_ids_json = rule.locationIds?.let { persistenceJson.encodeToString(it) },
                        condition_json = persistenceJson.encodeToString(Cond.serializer(), rule.condition),
                        schedule_json = persistenceJson.encodeToString(NotifySchedule.serializer(), rule.schedule),
                        hidden = if (rule.hidden) 1L else 0L,
                        created_at = rule.createdAt.toIsoFixed(),
                        modified_at = rule.modifiedAt.toIsoFixed(),
                    )
                }

                for ((key, value) in parsed.settings) db.appSettingQueries.upsert(key, value)

                var firedImported = 0
                for (id in parsed.firedNotificationIds) {
                    if (db.plannedNotificationQueries.selectById(id).executeAsOneOrNull() == null) {
                        val entry = SyncMerge.syntheticFiredHistoryEntry(id, parsed.exportedAt)
                        db.plannedNotificationQueries.upsert(
                            id = entry.id,
                            occurrence_id = entry.occurrenceId,
                            rule_id = entry.ruleId,
                            location_id = entry.locationId,
                            fire_at = entry.fireAt.toIsoFixed(),
                            status = entry.status.name,
                            precision = entry.precision.name,
                            title = entry.title,
                            body = entry.body,
                            created_at = entry.createdAt.toIsoFixed(),
                            fired_at = entry.firedAt?.toIsoFixed(),
                        )
                        firedImported++
                    }
                }

                SyncImportResult(
                    locationsImported = locationsToWrite.size,
                    rulesImported = rulesToWrite.size,
                    settingsImported = parsed.settings.size,
                    firedIdsImported = firedImported,
                )
            }
        }
}
