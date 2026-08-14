package dev.fritze.skyward.core.persistence

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import dev.fritze.skyward.core.model.GeoPoint
import dev.fritze.skyward.core.model.SavedLocation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlin.time.Instant

/** §11: `saved_location` — thin wrapper, no business logic (§11's own framing). */
class LocationRepo(private val db: SkywardDatabase) {

    fun observeAll(): Flow<List<SavedLocation>> =
        db.savedLocationQueries.selectAll().asFlow().mapToList(Dispatchers.Default).map { rows -> rows.map { it.toModel() } }

    suspend fun getAll(): List<SavedLocation> = withContext(Dispatchers.Default) {
        db.savedLocationQueries.selectAll().executeAsList().map { it.toModel() }
    }

    suspend fun getById(id: String): SavedLocation? = withContext(Dispatchers.Default) {
        db.savedLocationQueries.selectById(id).executeAsOneOrNull()?.toModel()
    }

    fun observeById(id: String): Flow<SavedLocation?> =
        db.savedLocationQueries.selectById(id).asFlow().mapToOneOrNull(Dispatchers.Default).map { it?.toModel() }

    suspend fun upsert(location: SavedLocation) = withContext(Dispatchers.Default) {
        db.transaction {
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
    }

    suspend fun delete(id: String) = withContext(Dispatchers.Default) {
        db.savedLocationQueries.deleteById(id)
    }

    private fun Saved_location.toModel() = SavedLocation(
        id = id,
        name = name,
        point = GeoPoint(lat_deg, lon_deg),
        isPrimary = is_primary != 0L,
        createdAt = Instant.parse(created_at),
        modifiedAt = Instant.parse(modified_at),
    )
}
