package dev.fritze.skyward.core.persistence

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * M0 acceptance check (§18): the SQLDelight schema (§11) must be wired up and
 * usable end to end. Runs against an in-memory SQLite DB via the desktop JDBC
 * driver, so it executes as a plain JVM test with no platform driver plumbing.
 */
class SkywardDatabaseSmokeTest {

    private fun newInMemoryDatabase(): SkywardDatabase {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        SkywardDatabase.Schema.create(driver)
        return SkywardDatabase(driver)
    }

    @Test
    fun savedLocationTableIsQueryable() {
        val queries = newInMemoryDatabase().savedLocationQueries
        assertEquals(0, queries.selectAll().executeAsList().size)
        assertNull(queries.selectById("missing-id").executeAsOneOrNull())
    }

    @Test
    fun appSettingUpsertAndRead() {
        val db = newInMemoryDatabase()
        db.appSettingQueries.upsert("schema_version", "1")
        val row = db.appSettingQueries.selectByKey("schema_version").executeAsOne()
        assertEquals("1", row.value_)
    }
}
