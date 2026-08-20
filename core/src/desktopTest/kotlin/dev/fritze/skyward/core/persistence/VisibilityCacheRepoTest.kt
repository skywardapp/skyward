package dev.fritze.skyward.core.persistence

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.fritze.skyward.core.model.Quality
import dev.fritze.skyward.core.model.VisibilityResult
import dev.fritze.skyward.core.visibility.VisibilityCacheEntry
import dev.fritze.skyward.core.visibility.VisibilityCacheKey
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/** §11: `visibility_cache` round-trips through [VisibilityCacheRepo] (issue #18). */
class VisibilityCacheRepoTest {

    private val now = Instant.parse("2026-01-01T00:00:00Z")

    private fun newRepo(): VisibilityCacheRepo {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        SkywardDatabase.Schema.create(driver)
        return VisibilityCacheRepo(SkywardDatabase(driver))
    }

    private fun entry(quality: Quality = Quality.GOOD, dataVersion: String = "v1") = VisibilityCacheEntry(
        dataVersion = dataVersion,
        result = VisibilityResult(quality != Quality.NONE, quality, null, null, null, null, null),
        computedAt = now,
    )

    @Test
    fun upsertAllThenGetAllRoundTripsEveryField() = runTest {
        val repo = newRepo()
        val key = VisibilityCacheKey("se:1", "home")
        val stored = entry(Quality.EXCELLENT, "abc123")

        repo.upsertAll(mapOf(key to stored))
        val loaded = repo.getAll()

        assertEquals(1, loaded.size)
        assertEquals(stored, loaded.getValue(key))
    }

    @Test
    fun upsertReplacesAnExistingEntryForTheSameKey() = runTest {
        val repo = newRepo()
        val key = VisibilityCacheKey("se:1", "home")
        repo.upsertAll(mapOf(key to entry(Quality.MARGINAL, "v1")))

        repo.upsertAll(mapOf(key to entry(Quality.EXCELLENT, "v2")))
        val loaded = repo.getAll()

        assertEquals(1, loaded.size, "same (occurrence, location) key -- one row")
        assertEquals("v2", loaded.getValue(key).dataVersion)
        assertEquals(Quality.EXCELLENT, loaded.getValue(key).result.quality)
    }

    @Test
    fun deleteByOccurrenceRemovesOnlyThatOccurrencesEntries() = runTest {
        val repo = newRepo()
        repo.upsertAll(
            mapOf(
                VisibilityCacheKey("se:1", "home") to entry(),
                VisibilityCacheKey("se:1", "office") to entry(),
                VisibilityCacheKey("se:2", "home") to entry(),
            ),
        )

        repo.deleteByOccurrence("se:1")
        val loaded = repo.getAll()

        assertEquals(1, loaded.size)
        assertTrue(loaded.containsKey(VisibilityCacheKey("se:2", "home")))
    }
}
