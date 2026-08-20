package dev.fritze.skyward.core.persistence

import dev.fritze.skyward.core.model.VisibilityResult
import dev.fritze.skyward.core.visibility.VisibilityCacheEntry
import dev.fritze.skyward.core.visibility.VisibilityCacheKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.time.Instant

/**
 * §11/§8.6: `visibility_cache` -- cached [dev.fritze.skyward.core.visibility.VisibilityModel.evaluate]
 * results, read/written through `VisibilityResultCache` (issue #18: the table existed but nothing
 * touched it).
 */
class VisibilityCacheRepo(private val db: SkywardDatabase) {

    /** Whole-table read: bounded by occurrence count x saved-location count, small enough to load in one pass (§9.5). */
    suspend fun getAll(): Map<VisibilityCacheKey, VisibilityCacheEntry> = withContext(Dispatchers.Default) {
        db.visibilityCacheQueries.selectAll().executeAsList().associate {
            VisibilityCacheKey(it.occurrence_id, it.location_id) to VisibilityCacheEntry(
                dataVersion = it.data_version,
                result = persistenceJson.decodeFromString(VisibilityResult.serializer(), it.result_json),
                computedAt = Instant.parse(it.computed_at),
            )
        }
    }

    suspend fun upsertAll(entries: Map<VisibilityCacheKey, VisibilityCacheEntry>) = withContext(Dispatchers.Default) {
        if (entries.isEmpty()) return@withContext
        db.transaction {
            for ((key, entry) in entries) {
                db.visibilityCacheQueries.upsert(
                    occurrence_id = key.occurrenceId,
                    location_id = key.locationId,
                    data_version = entry.dataVersion,
                    result_json = persistenceJson.encodeToString(VisibilityResult.serializer(), entry.result),
                    computed_at = entry.computedAt.toString(),
                )
            }
        }
    }

    /** §6.3: called when the planner drops an occurrence -- its cached verdicts are no longer meaningful. */
    suspend fun deleteByOccurrence(occurrenceId: String) = withContext(Dispatchers.Default) {
        db.visibilityCacheQueries.deleteByOccurrence(occurrenceId)
    }
}
