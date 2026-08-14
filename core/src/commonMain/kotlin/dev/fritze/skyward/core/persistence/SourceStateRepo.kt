package dev.fritze.skyward.core.persistence

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.time.Instant

/**
 * §11: `source_state` — per-source key/value blobs (`next_run_at`,
 * `backoff_count`, etags, `ovation_grid`, `diagnostics_json`, §6.2).
 */
class SourceStateRepo(private val db: SkywardDatabase) {

    suspend fun getBySource(sourceId: String): Map<String, ByteArray> = withContext(Dispatchers.Default) {
        db.sourceStateQueries.selectBySource(sourceId).executeAsList().associate { it.key to it.value_ }
    }

    suspend fun getValue(sourceId: String, key: String): ByteArray? = withContext(Dispatchers.Default) {
        db.sourceStateQueries.selectValue(sourceId, key).executeAsOneOrNull()?.value_
    }

    suspend fun upsert(sourceId: String, key: String, value: ByteArray, updatedAt: Instant) = withContext(Dispatchers.Default) {
        db.sourceStateQueries.upsert(sourceId, key, value, updatedAt.toString())
    }

    suspend fun delete(sourceId: String, key: String) = withContext(Dispatchers.Default) {
        db.sourceStateQueries.deleteBySourceAndKey(sourceId, key)
    }
}
