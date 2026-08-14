package dev.fritze.skyward.core.persistence

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import dev.fritze.skyward.core.model.Phenomenon
import dev.fritze.skyward.core.rules.Cond
import dev.fritze.skyward.core.rules.NotifySchedule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlin.time.Instant
import dev.fritze.skyward.core.rules.Rule as RuleModel

/** §11: `rule` (§9.1's `Rule`/`Cond`/`NotifySchedule`, split across `*_json` columns). */
class RuleRepo(private val db: SkywardDatabase) {

    /** Excludes system-generated hidden rules (mutes, one-off reminders, §13.3) — for the Rules list UI. */
    fun observeVisible(): Flow<List<RuleModel>> =
        db.ruleQueries.selectVisible().asFlow().mapToList(Dispatchers.Default).map { rows -> rows.map { it.toModel() } }

    fun observeAll(): Flow<List<RuleModel>> =
        db.ruleQueries.selectAll().asFlow().mapToList(Dispatchers.Default).map { rows -> rows.map { it.toModel() } }

    suspend fun getEnabled(): List<RuleModel> = withContext(Dispatchers.Default) {
        db.ruleQueries.selectEnabled().executeAsList().map { it.toModel() }
    }

    suspend fun getById(id: String): RuleModel? = withContext(Dispatchers.Default) {
        db.ruleQueries.selectById(id).executeAsOneOrNull()?.toModel()
    }

    suspend fun upsert(rule: RuleModel) = withContext(Dispatchers.Default) {
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

    suspend fun setEnabled(id: String, enabled: Boolean, modifiedAt: Instant) = withContext(Dispatchers.Default) {
        db.ruleQueries.updateEnabled(if (enabled) 1L else 0L, modifiedAt.toIsoFixed(), id)
    }

    suspend fun delete(id: String) = withContext(Dispatchers.Default) {
        db.ruleQueries.deleteById(id)
    }

    private fun Rule.toModel() = RuleModel(
        id = id,
        name = name,
        enabled = enabled != 0L,
        phenomena = persistenceJson.decodeFromString<Set<Phenomenon>>(phenomena_json),
        locationIds = location_ids_json?.let { persistenceJson.decodeFromString<List<String>>(it) },
        condition = persistenceJson.decodeFromString(Cond.serializer(), condition_json),
        schedule = persistenceJson.decodeFromString(NotifySchedule.serializer(), schedule_json),
        hidden = hidden != 0L,
        createdAt = Instant.parse(created_at),
        modifiedAt = Instant.parse(modified_at),
    )
}
