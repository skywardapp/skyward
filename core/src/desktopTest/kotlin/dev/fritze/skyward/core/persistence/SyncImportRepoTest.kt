package dev.fritze.skyward.core.persistence

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.fritze.skyward.core.model.GeoPoint
import dev.fritze.skyward.core.model.NotificationStatus
import dev.fritze.skyward.core.model.Phenomenon
import dev.fritze.skyward.core.model.PlannedNotification
import dev.fritze.skyward.core.model.Precision
import dev.fritze.skyward.core.model.SavedLocation
import dev.fritze.skyward.core.rules.Anchor
import dev.fritze.skyward.core.rules.Cond
import dev.fritze.skyward.core.rules.NotifySchedule
import dev.fritze.skyward.core.rules.Rule
import dev.fritze.skyward.core.sync.ParsedSyncFile
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/**
 * §12.3/issue #13: every location/rule/settings/notification-history write in one
 * [SyncImportRepo.applyImport] call must commit or roll back together.
 */
class SyncImportRepoTest {

    private val now = Instant.parse("2026-01-01T00:00:00Z")

    private fun newInMemoryDatabase(driver: SqlDriver): SkywardDatabase {
        SkywardDatabase.Schema.create(driver)
        return SkywardDatabase(driver)
    }

    private fun loc(id: String, modifiedAt: Instant = now, isPrimary: Boolean = false) =
        SavedLocation(id = id, name = id, point = GeoPoint(0.0, 0.0), isPrimary = isPrimary, createdAt = now, modifiedAt = modifiedAt)

    private fun rule(id: String, modifiedAt: Instant = now, name: String = id) = Rule(
        id = id, name = name, enabled = true, phenomena = setOf(Phenomenon.SOLAR_ECLIPSE), locationIds = null,
        condition = Cond.VisibleAtLocation(), schedule = NotifySchedule(listOf(1.days), Anchor.PEAK, notifyOnFirstSeen = false, quietHours = null),
        hidden = false, createdAt = now, modifiedAt = modifiedAt,
    )

    private fun emptyParsedFile(
        locations: List<SavedLocation> = emptyList(),
        rules: List<Rule> = emptyList(),
        settings: Map<String, String> = emptyMap(),
        firedNotificationIds: List<String> = emptyList(),
    ) = ParsedSyncFile(
        exportedAt = now, appVersion = "1.0", locations = locations, rules = rules, settings = settings,
        firedNotificationIds = firedNotificationIds, ruleWarnings = emptyList(), degradedRuleIds = emptySet(),
    )

    @Test
    fun mergeImportWritesNewAndNewerRecordsSkipsOlderAndDedupesFiredHistory() = runTest {
        val db = newInMemoryDatabase(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY))
        LocationRepo(db).upsert(loc("existing", modifiedAt = now))
        RuleRepo(db).upsert(rule("r-stale", modifiedAt = now))
        NotificationRepo(db).upsert(
            PlannedNotification(
                id = "already-fired", occurrenceId = "occ", ruleId = "", locationId = "", fireAt = now,
                status = NotificationStatus.FIRED, precision = Precision.EXACT, title = "", body = "", createdAt = now, firedAt = now,
            ),
        )

        val parsed = emptyParsedFile(
            locations = listOf(loc("new-loc", modifiedAt = now)),
            rules = listOf(rule("r-stale", modifiedAt = now - 1.days), rule("r-new", modifiedAt = now)),
            settings = mapOf("horizon_years" to "5"),
            firedNotificationIds = listOf("already-fired", "brand-new"),
        )

        val result = SyncImportRepo(db).applyImport(parsed, replaceEverything = false)

        assertEquals(1, result.locationsImported, "only the new location, the stale-modifiedAt one doesn't apply here")
        assertEquals(1, result.rulesImported, "r-stale's incoming copy is older than local and must be skipped")
        assertEquals(1, result.settingsImported)
        assertEquals(1, result.firedIdsImported, "already-fired is already present and must not be double-counted")

        assertEquals(setOf("existing", "new-loc"), LocationRepo(db).getAll().map { it.id }.toSet())
        assertEquals(now, RuleRepo(db).getById("r-stale")?.modifiedAt, "older incoming rule must not overwrite local")
        assertEquals(now, RuleRepo(db).getById("r-new")?.modifiedAt)
        assertEquals("5", SettingsRepo(db).get("horizon_years"))
        assertTrue(NotificationRepo(db).getById("brand-new") != null)
    }

    @Test
    fun replaceEverythingDeletesExistingLocationsAndRulesBeforeImporting() = runTest {
        val db = newInMemoryDatabase(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY))
        LocationRepo(db).upsert(loc("old-loc"))
        RuleRepo(db).upsert(rule("old-rule"))

        val parsed = emptyParsedFile(locations = listOf(loc("new-loc")), rules = listOf(rule("new-rule")))

        val result = SyncImportRepo(db).applyImport(parsed, replaceEverything = true)

        assertEquals(1, result.locationsImported)
        assertEquals(1, result.rulesImported)
        assertEquals(listOf("new-loc"), LocationRepo(db).getAll().map { it.id })
        assertEquals(listOf("new-rule"), RuleRepo(db).getAll().map { it.id })
    }

    /** Delegates every call to [delegate] except [execute], which throws once [failWhen] matches the SQL text. */
    private class FailingDriver(private val delegate: SqlDriver, private val failWhen: (String) -> Boolean) : SqlDriver by delegate {
        override fun execute(identifier: Int?, sql: String, parameters: Int, binders: (SqlPreparedStatement.() -> Unit)?): QueryResult<Long> {
            if (failWhen(sql)) throw RuntimeException("injected failure for test: $sql")
            return delegate.execute(identifier, sql, parameters, binders)
        }
    }

    @Test
    fun aFailureMidTransactionRollsBackEveryEarlierWriteInTheSameImport() = runTest {
        val realDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        SkywardDatabase.Schema.create(realDriver)
        // Settings are written after locations and rules (SyncImportRepo.applyImport), so failing
        // there proves earlier writes in the same transaction get rolled back, not just skipped.
        val failingDriver = FailingDriver(realDriver) { sql -> "app_setting" in sql }
        val db = SkywardDatabase(failingDriver)

        val parsed = emptyParsedFile(
            locations = listOf(loc("new-loc")),
            rules = listOf(rule("new-rule")),
            settings = mapOf("horizon_years" to "5"),
            firedNotificationIds = listOf("brand-new"),
        )

        assertFailsWith<RuntimeException> { SyncImportRepo(db).applyImport(parsed, replaceEverything = false) }

        assertTrue(LocationRepo(db).getAll().isEmpty(), "location write must be rolled back with the rest of the transaction")
        assertTrue(RuleRepo(db).getAll().isEmpty(), "rule write must be rolled back with the rest of the transaction")
        assertNull(NotificationRepo(db).getById("brand-new"), "notification write never even ran")
    }
}
