package dev.fritze.skyward.desktop.data

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.fritze.skyward.core.model.Phenomenon
import dev.fritze.skyward.core.persistence.LocationRepo
import dev.fritze.skyward.core.persistence.NotificationRepo
import dev.fritze.skyward.core.persistence.OccurrenceRepo
import dev.fritze.skyward.core.persistence.RuleRepo
import dev.fritze.skyward.core.persistence.SettingsRepo
import dev.fritze.skyward.core.persistence.SkywardDatabase
import dev.fritze.skyward.core.persistence.SourceStateRepo
import dev.fritze.skyward.core.persistence.SyncImportRepo
import dev.fritze.skyward.core.persistence.VisibilityCacheRepo
import dev.fritze.skyward.core.planner.ReplanCoordinator
import dev.fritze.skyward.core.rules.defaultRules
import dev.fritze.skyward.core.sources.AuroraSource
import dev.fritze.skyward.core.sources.EventSource
import dev.fritze.skyward.core.sources.defaultComputedSources
import dev.fritze.skyward.core.sources.defaultPolledSources
import dev.fritze.skyward.core.visibility.OvationGrid
import dev.fritze.skyward.core.visibility.VisibilityModel
import dev.fritze.skyward.core.visibility.defaultVisibilityModels
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.nio.file.Path
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Composition root for the desktop app — the `:desktopApp` twin of Android's
 * `AppContainer` (§4.1: three modules, no DI framework). One instance for
 * the process lifetime, owned by `main`.
 *
 * The visibility-model map and source lists are the same shared `:core`
 * definitions Android's `AppContainer` uses: the whole point of §4.1 is
 * that only the UI differs between the two frontends.
 */
class DesktopContainer(
    private val driver: SqlDriver,
    /** Desktop has no WorkManager: everything runs inside this one process scope (§4.3, §10.3). */
    val applicationScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    val database: SkywardDatabase = SkywardDatabase(driver)

    val locationRepo = LocationRepo(database)
    val occurrenceRepo = OccurrenceRepo(database)
    val ruleRepo = RuleRepo(database)
    val notificationRepo = NotificationRepo(database)
    val sourceStateRepo = SourceStateRepo(database)
    val settingsRepo = SettingsRepo(database)
    val syncImportRepo = SyncImportRepo(database)
    val visibilityCacheRepo = VisibilityCacheRepo(database)

    val visibilityModels: Map<Phenomenon, VisibilityModel> = defaultVisibilityModels

    val computedSources: List<EventSource> = defaultComputedSources
    val polledSources: List<EventSource> = defaultPolledSources
    val allSources: List<EventSource> get() = computedSources + polledSources

    val replanCoordinator = ReplanCoordinator(
        occurrenceRepo, locationRepo, ruleRepo, notificationRepo, visibilityCacheRepo, visibilityModels,
        ovationGridProvider = { latestOvationGrid() },
    )

    val sourceRunner = dev.fritze.skyward.core.sources.SourceRunner(
        allSources, occurrenceRepo, sourceStateRepo, settingsRepo, ruleRepo, locationRepo, visibilityCacheRepo,
        onOccurrencesChanged = { now -> replan(now) },
    )

    /** The latest persisted OVATION nowcast grid (§7.3.1), or null if none has been fetched yet. */
    suspend fun latestOvationGrid(): OvationGrid? = AuroraSource.loadOvationGrid(sourceStateRepo)

    /**
     * §9.7. Unlike Android there is no OS alarm layer to sync afterwards —
     * `DesktopScheduler` reads the same `planned_notification` rows straight
     * out of the DB, so persisting them *is* the scheduling (§10.3).
     */
    suspend fun replan(now: Instant = Clock.System.now()) {
        replanCoordinator.replan(now)
    }

    /** §9.6: shipped default rules, created once on first launch and thereafter user-owned. */
    suspend fun ensureDefaultRulesSeeded() {
        if (settingsRepo.get(KEY_DEFAULT_RULES_SEEDED) == "true") return
        for (rule in defaultRules(Clock.System.now())) ruleRepo.upsert(rule)
        settingsRepo.set(KEY_DEFAULT_RULES_SEEDED, "true")
    }

    fun close() {
        applicationScope.cancel()
        driver.close()
    }

    /**
     * What opening a database file at a given `user_version` should do.
     *
     * Split out from [migrateIfNeeded] so the decision can be tested on its
     * own: while the schema is at version 1 the [MIGRATE] branch is
     * unreachable through the real schema — the only value below 1 is 0, which
     * means "no database yet" — and an untestable branch is one that quietly
     * rots until the first `.sqm` file makes it matter.
     */
    internal enum class SchemaAction { CREATE, MIGRATE, NONE }

    companion object {
        const val KEY_DEFAULT_RULES_SEEDED = "default_rules_seeded"

        /** §10.3: closing the window hides to tray instead of exiting. */
        const val KEY_BACKGROUND_MODE = "background_mode"

        /** §10.3: XDG autostart entry / Flatpak background portal request. */
        const val KEY_AUTOSTART = "autostart"

        fun open(paths: DesktopPaths = DesktopPaths()): DesktopContainer =
            DesktopContainer(openDriver(paths.databaseFile()))

        /**
         * §11: JdbcSqliteDriver + Xerial. SQLDelight's JDBC driver, unlike the
         * Android one, does not create or migrate the schema for us — the
         * `user_version` pragma is the only place the on-disk schema version
         * lives, so it has to be read, acted on and written back by hand.
         */
        fun openDriver(databaseFile: Path): SqlDriver {
            // Created owner-only *before* Xerial opens it: SQLite creates a
            // missing database file with the process umask, which on a stock
            // `umask 022` desktop means every saved location's coordinates are
            // world-readable (P1). Creating it empty first costs nothing —
            // SQLite treats a zero-length file as a fresh database.
            PrivateFiles.createFile(databaseFile)
            val driver = JdbcSqliteDriver("jdbc:sqlite:${databaseFile.toAbsolutePath()}")
            migrateIfNeeded(driver)
            // The write-ahead log and shared-memory files are SQLite's to
            // create, and they hold the same rows the database does. The
            // containing directory is owner-only too, so this is belt and
            // braces for a data dir a user has deliberately opened up.
            for (suffix in SQLITE_SIDECAR_SUFFIXES) {
                PrivateFiles.restrictFile(databaseFile.resolveSibling(databaseFile.fileName.toString() + suffix))
            }
            return driver
        }

        /** SQLite's WAL sidecars, which carry the same data as the database itself. */
        private val SQLITE_SIDECAR_SUFFIXES = listOf("-wal", "-shm", "-journal")

        internal fun schemaAction(currentVersion: Long, schemaVersion: Long): SchemaAction = when {
            currentVersion == 0L -> SchemaAction.CREATE
            currentVersion < schemaVersion -> SchemaAction.MIGRATE
            // current >= schema.version. Equal needs nothing; greater is a DB
            // written by a newer build, and is left alone rather than
            // "migrated" backwards — SQLDelight has no downgrade path, and
            // clobbering user data is worse than the failure the queries will
            // report honestly.
            else -> SchemaAction.NONE
        }

        internal fun migrateIfNeeded(driver: SqlDriver) {
            val schema = SkywardDatabase.Schema
            val current = driver.executeQuery(
                identifier = null,
                sql = "PRAGMA user_version;",
                mapper = { cursor -> QueryResult.Value(if (cursor.next().value) cursor.getLong(0) ?: 0L else 0L) },
                parameters = 0,
            ).value

            when (schemaAction(current, schema.version)) {
                SchemaAction.CREATE -> {
                    schema.create(driver).value
                    setUserVersion(driver, schema.version)
                }
                SchemaAction.MIGRATE -> {
                    schema.migrate(driver, current, schema.version).value
                    setUserVersion(driver, schema.version)
                }
                SchemaAction.NONE -> Unit
            }
        }

        private fun setUserVersion(driver: SqlDriver, version: Long) {
            // PRAGMA user_version does not accept a bound parameter, so this has to be
            // interpolated; `version` is a Long from generated code, never user input.
            driver.execute(identifier = null, sql = "PRAGMA user_version = $version;", parameters = 0).value
        }
    }
}
