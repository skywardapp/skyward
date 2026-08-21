package dev.fritze.skyward.core.persistence

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import dev.fritze.skyward.core.model.Certainty
import dev.fritze.skyward.core.model.OccurrencePayload
import dev.fritze.skyward.core.model.Phenomenon
import dev.fritze.skyward.core.model.TimeWindow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import kotlin.time.Instant
import dev.fritze.skyward.core.model.Occurrence as OccurrenceModel

/**
 * §11: `occurrence`. Deliberately low-level ([upsert] takes `firstSeenAt`
 * explicitly, [getFirstSeenAt] exists to look it up) — the "preserve
 * first_seen_at across re-fetches, drop withdrawn FORECAST rows" algorithm
 * (§6.3) is SourceRunner's job, not this repo's (§11: "no business logic
 * in repos").
 */
class OccurrenceRepo(
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

    fun observeAll(): Flow<List<OccurrenceModel>> =
        db.occurrenceQueries.selectAll().asFlow().mapToList(sqlContext).map { rows -> rows.map { it.toModel() } }

    suspend fun getAll(): List<OccurrenceModel> = withContext(sqlContext) {
        db.occurrenceQueries.selectAll().executeAsList().map { it.toModel() }
    }

    suspend fun getById(id: String): OccurrenceModel? = withContext(sqlContext) {
        db.occurrenceQueries.selectById(id).executeAsOneOrNull()?.toModel()
    }

    suspend fun getBySource(sourceId: String): List<OccurrenceModel> = withContext(sqlContext) {
        db.occurrenceQueries.selectBySource(sourceId).executeAsList().map { it.toModel() }
    }

    suspend fun getIdsBySource(sourceId: String): Set<String> = withContext(sqlContext) {
        db.occurrenceQueries.selectIdsBySource(sourceId).executeAsList().toSet()
    }

    suspend fun getFirstSeenAt(id: String): Instant? = withContext(sqlContext) {
        db.occurrenceQueries.selectById(id).executeAsOneOrNull()?.first_seen_at?.let(Instant::parse)
    }

    suspend fun upsert(occurrence: OccurrenceModel, firstSeenAt: Instant) = withContext(sqlContext) {
        db.occurrenceQueries.upsert(
            id = occurrence.id,
            phenomenon = occurrence.phenomenon.name,
            source_id = occurrence.sourceId,
            title = occurrence.title,
            window_start = occurrence.window.start.toString(),
            window_end = occurrence.window.end.toString(),
            peak_time = occurrence.peakTime?.toString(),
            certainty = occurrence.certainty.name,
            payload_json = persistenceJson.encodeToString(OccurrencePayload.serializer(), occurrence.payload),
            fetched_at = occurrence.fetchedAt.toString(),
            expires_at = occurrence.expiresAt?.toString(),
            first_seen_at = firstSeenAt.toString(),
        )
    }

    suspend fun deleteById(id: String) = withContext(sqlContext) {
        db.occurrenceQueries.deleteById(id)
    }

    private fun Occurrence.toModel() = OccurrenceModel(
        id = id,
        phenomenon = Phenomenon.valueOf(phenomenon),
        sourceId = source_id,
        title = title,
        window = TimeWindow(Instant.parse(window_start), Instant.parse(window_end)),
        peakTime = peak_time?.let(Instant::parse),
        certainty = Certainty.valueOf(certainty),
        payload = persistenceJson.decodeFromString(OccurrencePayload.serializer(), payload_json),
        fetchedAt = Instant.parse(fetched_at),
        expiresAt = expires_at?.let(Instant::parse),
    )
}
