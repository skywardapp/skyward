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
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/** §11/§10.4: `planned_notification`. */
class NotificationRepo(
    private val db: SkywardDatabase,
    /**
     * The context this repo's SQL runs on. Defaults to [Dispatchers.Default],
     * which is what production uses; a test that needs the writes to land on a
     * scheduler it controls (rather than on a real thread pool, at a real
     * moment) passes its own. §17 injects clocks everywhere for the same reason
     * — a test that proves a negative with a real sleep only proves that CI was
     * fast enough that time.
     */
    private val sqlContext: CoroutineContext = Dispatchers.Default,
) {

    companion object {
        /** §10.4: "FIRED rows are permanent history (auto-pruned after 180 days)." */
        val FIRED_RETENTION = 180.days
    }

    fun observeAll(): Flow<List<PlannedNotification>> =
        db.plannedNotificationQueries.selectAll().asFlow().mapToList(sqlContext).map { rows -> rows.map { it.toModel() } }

    suspend fun getAll(): List<PlannedNotification> = withContext(sqlContext) {
        db.plannedNotificationQueries.selectAll().executeAsList().map { it.toModel() }
    }

    suspend fun getById(id: String): PlannedNotification? = withContext(sqlContext) {
        db.plannedNotificationQueries.selectById(id).executeAsOneOrNull()?.toModel()
    }

    suspend fun getByOccurrence(occurrenceId: String): List<PlannedNotification> = withContext(sqlContext) {
        db.plannedNotificationQueries.selectByOccurrence(occurrenceId).executeAsList().map { it.toModel() }
    }

    suspend fun getPendingDue(now: Instant): List<PlannedNotification> = withContext(sqlContext) {
        db.plannedNotificationQueries.selectPendingDue(now.toIsoFixed()).executeAsList().map { it.toModel() }
    }

    suspend fun upsert(notification: PlannedNotification) = withContext(sqlContext) {
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

    suspend fun updateStatus(id: String, status: NotificationStatus, firedAt: Instant?) = withContext(sqlContext) {
        db.plannedNotificationQueries.updateStatus(status.name, firedAt?.toIsoFixed(), id)
    }

    suspend fun updatePrecision(id: String, precision: Precision) = withContext(sqlContext) {
        db.plannedNotificationQueries.updatePrecision(precision.name, id)
    }

    suspend fun deleteById(id: String) = withContext(sqlContext) {
        db.plannedNotificationQueries.deleteById(id)
    }

    suspend fun pruneFiredBefore(instant: Instant) = withContext(sqlContext) {
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
