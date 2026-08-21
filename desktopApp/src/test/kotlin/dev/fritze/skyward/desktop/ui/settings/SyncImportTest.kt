package dev.fritze.skyward.desktop.ui.settings

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.fritze.skyward.core.model.GeoPoint
import dev.fritze.skyward.core.model.Phenomenon
import dev.fritze.skyward.core.model.SavedLocation
import dev.fritze.skyward.core.persistence.SkywardDatabase
import dev.fritze.skyward.core.rules.Anchor
import dev.fritze.skyward.core.rules.Cond
import dev.fritze.skyward.core.rules.NotifySchedule
import dev.fritze.skyward.core.rules.Rule
import dev.fritze.skyward.core.sync.SyncCodec
import dev.fritze.skyward.core.sync.SyncFile
import dev.fritze.skyward.desktop.data.DesktopContainer
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/**
 * §12.3 import from the desktop UI's entry point. The point of these tests is
 * that [runImport] delegates to `:core`'s `SyncImportRepo` rather than
 * re-implementing the merge locally (P2/§4.1, issue #50): the rollback case
 * below can only pass if every write shares one transaction.
 */
class SyncImportTest {

    private val now = Instant.parse("2026-01-01T00:00:00Z")

    private fun loc(id: String, modifiedAt: Instant = now) = SavedLocation(
        id = id, name = id, point = GeoPoint(52.0, 7.6), isPrimary = false, createdAt = now, modifiedAt = modifiedAt,
    )

    private fun rule(id: String, modifiedAt: Instant = now) = Rule(
        id = id, name = id, enabled = true, phenomena = setOf(Phenomenon.SOLAR_ECLIPSE), locationIds = null,
        condition = Cond.VisibleAtLocation(),
        schedule = NotifySchedule(listOf(1.days), Anchor.PEAK, notifyOnFirstSeen = false, quietHours = null),
        hidden = false, createdAt = now, modifiedAt = modifiedAt,
    )

    private fun exportFile(
        locations: List<SavedLocation> = emptyList(),
        rules: List<Rule> = emptyList(),
        settings: Map<String, String> = emptyMap(),
        firedNotificationIds: List<String> = emptyList(),
    ): File {
        val text = SyncCodec.export(
            SyncFile(
                exportedAt = now, appVersion = "test", locations = locations, rules = rules,
                settings = settings, firedNotificationIds = firedNotificationIds,
            ),
        )
        val file = createTempDirectory("skyward-sync-test").resolve("export.json").toFile()
        file.writeText(text)
        return file
    }

    /** In-memory database plus the schema the JDBC driver does not create by itself (§11). */
    private fun newDriver(): SqlDriver =
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { SkywardDatabase.Schema.create(it) }

    @Test
    fun mergeImportKeepsLocalDataAndTakesOnlyNewerOrMissingRecords() = runBlocking {
        val container = DesktopContainer(newDriver())
        container.locationRepo.upsert(loc("existing"))
        container.ruleRepo.upsert(rule("r-stale", modifiedAt = now))

        val source = exportFile(
            locations = listOf(loc("new-loc")),
            rules = listOf(rule("r-stale", modifiedAt = now - 1.days), rule("r-new")),
            settings = mapOf("horizon_years" to "5"),
        )

        val status = runImport(container, source, replaceEverything = false)

        assertTrue(status.startsWith("Imported 1 locations, 1 rules, 1 settings, 0 history entries."), status)
        assertEquals(setOf("existing", "new-loc"), container.locationRepo.getAll().mapTo(mutableSetOf()) { it.id })
        assertEquals(now, container.ruleRepo.getById("r-stale")?.modifiedAt, "an older incoming rule must not overwrite local")
        assertEquals("5", container.settingsRepo.get("horizon_years"))
        container.close()
    }

    @Test
    fun replaceEverythingWipesLocalLocationsAndRulesBeforeImporting() = runBlocking {
        val container = DesktopContainer(newDriver())
        container.locationRepo.upsert(loc("old-loc"))
        container.ruleRepo.upsert(rule("old-rule"))

        val source = exportFile(locations = listOf(loc("new-loc")), rules = listOf(rule("new-rule")))

        runImport(container, source, replaceEverything = true)

        assertEquals(listOf("new-loc"), container.locationRepo.getAll().map { it.id })
        assertEquals(listOf("new-rule"), container.ruleRepo.getAll().map { it.id })
        container.close()
    }

    /**
     * Issue #50's failure scenario: the desktop import used to run the merge as
     * sequential per-row repo calls, so a failure partway through "replace
     * everything" left a wiped, half-imported database behind. Nothing survives
     * a mid-import failure now — not even the replace-mode deletions.
     */
    @Test
    fun aFailureMidImportLeavesTheDatabaseExactlyAsItWas() = runBlocking {
        val realDriver = newDriver()
        // Settings are written after the deletions and after locations/rules, so
        // failing there covers everything earlier in the same transaction.
        val container = DesktopContainer(FailingDriver(realDriver) { "app_setting" in it })
        container.locationRepo.upsert(loc("old-loc"))
        container.ruleRepo.upsert(rule("old-rule"))

        val source = exportFile(
            locations = listOf(loc("new-loc")),
            rules = listOf(rule("new-rule")),
            settings = mapOf("horizon_years" to "5"),
        )

        val status = runImport(container, source, replaceEverything = true)

        assertTrue(status.startsWith("Import failed:"), status)
        assertEquals(listOf("old-loc"), container.locationRepo.getAll().map { it.id }, "the replace-mode wipe must roll back too")
        assertEquals(listOf("old-rule"), container.ruleRepo.getAll().map { it.id })
        container.close()
    }

    /** Delegates every call except [execute], which throws once [failWhen] matches the SQL text. */
    private class FailingDriver(private val delegate: SqlDriver, private val failWhen: (String) -> Boolean) : SqlDriver by delegate {
        override fun execute(identifier: Int?, sql: String, parameters: Int, binders: (SqlPreparedStatement.() -> Unit)?): QueryResult<Long> {
            if (failWhen(sql)) throw RuntimeException("injected failure for test: $sql")
            return delegate.execute(identifier, sql, parameters, binders)
        }
    }
}
