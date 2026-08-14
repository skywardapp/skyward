package dev.fritze.skyward.core.sync

import dev.fritze.skyward.core.model.NotificationStatus
import dev.fritze.skyward.core.model.PlannedNotification
import dev.fritze.skyward.core.model.Precision
import kotlin.time.Instant

/**
 * §12.3: "Match by id. ... incoming wins iff its modifiedAt is newer;
 * otherwise keep local. Never delete local records absent from the file."
 * Pure function -- callers (the sync ViewModel) own the actual repo writes,
 * so this stays testable without a database.
 */
object SyncMerge {

    /** The subset of [incoming] that should be written locally: new ids, or ids where [incoming] is strictly newer. */
    fun <T> newerOrMissing(local: List<T>, incoming: List<T>, id: (T) -> String, modifiedAt: (T) -> Instant): List<T> {
        val localById = local.associateBy(id)
        return incoming.filter { candidate ->
            val existing = localById[id(candidate)]
            existing == null || modifiedAt(candidate) > modifiedAt(existing)
        }
    }

    /** §12.1/§12.3: union-merge fired-notification history keys as synthetic FIRED rows, so a second device doesn't re-notify past events. */
    fun syntheticFiredHistoryEntry(notificationId: String, importedAt: Instant): PlannedNotification = PlannedNotification(
        id = notificationId,
        occurrenceId = notificationId.substringBefore('|'),
        ruleId = "",
        locationId = "",
        fireAt = importedAt,
        status = NotificationStatus.FIRED,
        precision = Precision.EXACT,
        title = "",
        body = "",
        createdAt = importedAt,
        firedAt = importedAt,
    )
}
