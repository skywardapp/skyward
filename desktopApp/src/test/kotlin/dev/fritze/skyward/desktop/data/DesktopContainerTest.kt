package dev.fritze.skyward.desktop.data

import dev.fritze.skyward.core.model.GeoPoint
import dev.fritze.skyward.core.model.SavedLocation
import dev.fritze.skyward.core.persistence.SkywardDatabase
import kotlinx.coroutines.runBlocking
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * §11: the JDBC driver, unlike Android's, creates nothing by itself — the
 * schema create/migrate handshake is [DesktopContainer]'s own code, so it is
 * the one bit of persistence wiring that can silently be wrong on desktop.
 */
class DesktopContainerTest {

    private val now = Instant.parse("2026-08-14T00:00:00Z")

    @Test
    fun createsTheSchemaOnAFreshDatabaseFileAndPersistsAcrossReopen() = runBlocking {
        val file = createTempDirectory("skyward-test").resolve("skyward.db")

        val first = DesktopContainer(DesktopContainer.openDriver(file))
        first.locationRepo.upsert(
            SavedLocation("home", "Home", GeoPoint(52.0, 7.6), isPrimary = true, createdAt = now, modifiedAt = now),
        )
        first.close()

        val second = DesktopContainer(DesktopContainer.openDriver(file))
        val locations = second.locationRepo.getAll()
        second.close()

        assertEquals(1, locations.size)
        assertEquals("Home", locations.single().name)
    }

    @Test
    fun reopeningAnExistingDatabaseDoesNotRecreateTheSchema() = runBlocking {
        val file = createTempDirectory("skyward-test").resolve("skyward.db")

        // A second `Schema.create` against the same file would fail on the
        // already-existing tables; the user_version guard is what prevents it.
        DesktopContainer.openDriver(file).close()
        val driver = DesktopContainer.openDriver(file)
        val database = SkywardDatabase(driver)
        val settings = database.appSettingQueries.selectAll().executeAsList()
        driver.close()

        assertTrue(settings.isEmpty())
    }

    @Test
    fun stampsTheSchemaVersionSoLaterMigrationsKnowWhereTheyStand() = runBlocking {
        val file = createTempDirectory("skyward-test").resolve("skyward.db")
        val driver = DesktopContainer.openDriver(file)
        val version = driver.executeQuery(
            identifier = null,
            sql = "PRAGMA user_version;",
            mapper = { cursor ->
                app.cash.sqldelight.db.QueryResult.Value(if (cursor.next().value) cursor.getLong(0) ?: 0L else 0L)
            },
            parameters = 0,
        ).value
        driver.close()

        assertEquals(SkywardDatabase.Schema.version, version)
    }

    @Test
    fun aDatabaseBehindTheCurrentSchemaIsMigratedForward() = runBlocking {
        val file = createTempDirectory("skyward-test").resolve("skyward.db")
        val container = DesktopContainer(DesktopContainer.openDriver(file))
        container.locationRepo.upsert(
            SavedLocation("home", "Home", GeoPoint(52.0, 7.6), isPrimary = true, createdAt = now, modifiedAt = now),
        )
        container.close()

        // Pretend the file was written by an older build. The schema is
        // already at the current shape, so `migrate` has nothing to do — what
        // is under test is that the stamp is brought forward rather than left
        // behind, which would re-run the migration on every single launch.
        val driver = DesktopContainer.openDriver(file)
        setUserVersion(driver, 1L)
        DesktopContainer.migrateIfNeeded(driver)
        assertEquals(SkywardDatabase.Schema.version, readUserVersion(driver))
        driver.close()

        val reopened = DesktopContainer(DesktopContainer.openDriver(file))
        val locations = reopened.locationRepo.getAll()
        reopened.close()
        assertEquals(1, locations.size, "migration must not lose rows")
    }

    @Test
    fun aDatabaseFromANewerBuildIsLeftAlone() = runBlocking {
        // SQLDelight has no downgrade path. Stamping it back — or worse,
        // running `create` over live tables — would destroy a user's data on
        // the first launch of an older build.
        val file = createTempDirectory("skyward-test").resolve("skyward.db")
        val container = DesktopContainer(DesktopContainer.openDriver(file))
        container.locationRepo.upsert(
            SavedLocation("home", "Home", GeoPoint(52.0, 7.6), isPrimary = true, createdAt = now, modifiedAt = now),
        )
        container.close()

        val future = SkywardDatabase.Schema.version + 5
        val driver = DesktopContainer.openDriver(file)
        setUserVersion(driver, future)
        DesktopContainer.migrateIfNeeded(driver)
        assertEquals(future, readUserVersion(driver))
        driver.close()

        val reopened = DesktopContainer(DesktopContainer.openDriver(file))
        val locations = reopened.locationRepo.getAll()
        reopened.close()
        assertEquals(1, locations.size, "a newer database must be left intact")
    }

    private fun readUserVersion(driver: app.cash.sqldelight.db.SqlDriver): Long = driver.executeQuery(
        identifier = null,
        sql = "PRAGMA user_version;",
        mapper = { cursor ->
            app.cash.sqldelight.db.QueryResult.Value(if (cursor.next().value) cursor.getLong(0) ?: 0L else 0L)
        },
        parameters = 0,
    ).value

    private fun setUserVersion(driver: app.cash.sqldelight.db.SqlDriver, version: Long) {
        driver.execute(identifier = null, sql = "PRAGMA user_version = $version;", parameters = 0).value
    }

    @Test
    fun defaultRulesAreSeededExactlyOnce() = runBlocking {
        val file = createTempDirectory("skyward-test").resolve("skyward.db")
        val container = DesktopContainer(DesktopContainer.openDriver(file))

        container.ensureDefaultRulesSeeded()
        val afterFirst = container.ruleRepo.getAll()
        assertTrue(afterFirst.isNotEmpty(), "expected the §9.6 shipped rules")

        // A user who deletes a shipped rule must not have it resurrected on
        // the next launch.
        container.ruleRepo.delete(afterFirst.first().id)
        container.ensureDefaultRulesSeeded()
        val afterSecond = container.ruleRepo.getAll()
        container.close()

        assertEquals(afterFirst.size - 1, afterSecond.size)
    }
}
