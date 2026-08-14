package dev.fritze.skyward.core.persistence

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * §11: `app_setting` — flat key/value store. Known keys: `horizon_years`,
 * `units`, `source.<id>.enabled`, `source.<id>.settings_json`,
 * `aurora_tier_state`, `onboarding_done`, `background_mode` (desktop),
 * `theme`, `schema_version`.
 */
class SettingsRepo(private val db: SkywardDatabase) {

    fun observeAll(): Flow<Map<String, String>> =
        db.appSettingQueries.selectAll().asFlow().mapToList(Dispatchers.Default).map { rows -> rows.associate { it.key to it.value_ } }

    fun observe(key: String): Flow<String?> =
        db.appSettingQueries.selectByKey(key).asFlow().mapToOneOrNull(Dispatchers.Default).map { it?.value_ }

    suspend fun get(key: String): String? = withContext(Dispatchers.Default) {
        db.appSettingQueries.selectByKey(key).executeAsOneOrNull()?.value_
    }

    suspend fun set(key: String, value: String) = withContext(Dispatchers.Default) {
        db.appSettingQueries.upsert(key, value)
    }

    suspend fun delete(key: String) = withContext(Dispatchers.Default) {
        db.appSettingQueries.deleteByKey(key)
    }

    // Typed convenience for the handful of keys read outside a raw settings screen.

    fun observeOnboardingDone(): Flow<Boolean> = observe(KEY_ONBOARDING_DONE).map { it == "true" }
    suspend fun setOnboardingDone(done: Boolean) = set(KEY_ONBOARDING_DONE, done.toString())

    suspend fun getHorizonYears(): Int = get(KEY_HORIZON_YEARS)?.toIntOrNull() ?: DEFAULT_HORIZON_YEARS
    suspend fun setHorizonYears(years: Int) = set(KEY_HORIZON_YEARS, years.toString())

    suspend fun isSourceEnabled(sourceId: String, defaultValue: Boolean = true): Boolean =
        get("source.$sourceId.enabled")?.let { it == "true" } ?: defaultValue
    suspend fun setSourceEnabled(sourceId: String, enabled: Boolean) = set("source.$sourceId.enabled", enabled.toString())

    private companion object {
        const val KEY_ONBOARDING_DONE = "onboarding_done"
        const val KEY_HORIZON_YEARS = "horizon_years"
        const val DEFAULT_HORIZON_YEARS = 3
    }
}
