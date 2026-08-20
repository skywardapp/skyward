package dev.fritze.skyward.core.persistence

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import dev.fritze.skyward.core.model.NotificationStatus
import dev.fritze.skyward.core.model.PlannedNotification
import dev.fritze.skyward.core.model.Precision
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/** §11/§10.4: `planned_notification`. */
class NotificationRepo(private val db: SkywardDatabase) {

    companion object {
        /** §10.4: "FIRED rows are permanent history (auto-pruned after 180 days)." */
        val FIRED_RETENTION = 180.days
    }

    fun observeAll(): Flow<List<PlannedNotification>> =
        db.plannedNotificationQueries.selectAll().asFlow().mapToList(Dispatchers.Default).map { rows -> rows.map { it.toModel() } }

    suspend fun getAll(): List<PlannedNotification> = withContext(Dispatchers.Default) {
        db.plannedNotificationQueries.selectAll().executeAsList().map { it.toModel() }
    }

    suspend fun getById(id: String): PlannedNotification? = withContext(Dispatchers.Default) {
        db.plannedNotificationQueries.selectById(id).executeAsOneOrNull()?.toModel()
    }

    suspend fun getByOccurrence(occurrenceId: String): List<PlannedNotification> = withContext(Dispatchers.Default) {
        db.plannedNotificationQueries.selectByOccurrence(occurrenceId).executeAsList().map { it.toModel() }
    }

    suspend fun getPendingDue(now: Instant): List<PlannedNotification> = withContext(Dispatchers.Default) {
        db.plannedNotificationQueries.selectPendingDue(now.toIsoFixed()).executeAsList().map { it.toModel() }
    }

    suspend fun upsert(notification: PlannedNotification) = withContext(Dispatchers.Default) {
        db.plannedNotificationQueries.upsert(
            id = notification.id,
            occurrence_id = notification.occurrenceId,
            rule_id = notification.ruleId,
            location_id = notification.locationId,
            fire_at = notification.fireAt.toIsoFixed(),
            status = notification.status.name,
            precision = notification.precision.name,
            title = notification.title,
            body = notification.body,
            created_at = notification.createdAt.toIsoFixed(),
            fired_at = notification.firedAt?.toIsoFixed(),
        )
    }

    suspend fun updateStatus(id: String, status: NotificationStatus, firedAt: Instant?) = withContext(Dispatchers.Default) {
        db.plannedNotificationQueries.updateStatus(status.name, firedAt?.toIsoFixed(), id)
    }

    suspend fun updatePrecision(id: String, precision: Precision) = withContext(Dispatchers.Default) {
        db.plannedNotificationQueries.updatePrecision(precision.name, id)
    }

    suspend fun deleteById(id: String) = withContext(Dispatchers.Default) {
        db.plannedNotificationQueries.deleteById(id)
    }

    suspend fun pruneFiredBefore(instant: Instant) = withContext(Dispatchers.Default) {
        db.plannedNotificationQueries.pruneFiredBefore(instant.toIsoFixed())
    }

    private fun Planned_notification.toModel() = PlannedNotification(
        id = id,
        occurrenceId = occurrence_id,
        ruleId = rule_id,
        locationId = location_id,
        fireAt = Instant.parse(fire_at),
        status = NotificationStatus.valueOf(status),
        precision = Precision.valueOf(precision),
        title = title,
        body = body,
        createdAt = Instant.parse(created_at),
        firedAt = fired_at?.let(Instant::parse),
    )
}
