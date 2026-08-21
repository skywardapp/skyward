package dev.fritze.skyward.core.persistence

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.fritze.skyward.core.model.Certainty
import dev.fritze.skyward.core.model.GeoPoint
import dev.fritze.skyward.core.model.NotificationStatus
import dev.fritze.skyward.core.model.Occurrence
import dev.fritze.skyward.core.model.Phenomenon
import dev.fritze.skyward.core.model.PlannedNotification
import dev.fritze.skyward.core.model.Precision
import dev.fritze.skyward.core.model.Quality
import dev.fritze.skyward.core.model.SavedLocation
import dev.fritze.skyward.core.model.SolarEclipseKind
import dev.fritze.skyward.core.model.SolarEclipsePayload
import dev.fritze.skyward.core.model.TimeWindow
import dev.fritze.skyward.core.rules.Anchor
import dev.fritze.skyward.core.rules.Cond
import dev.fritze.skyward.core.rules.NotifySchedule
import dev.fritze.skyward.core.rules.Rule
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/**
 * §11: round-trips every repo's [Repo].upsert against an in-memory SQLite
 * DB and back through its Flow-returning reads, since each repo hand-writes
 * the model<->row mapping the generated SQLDelight types don't give for free.
 */
class RepositoriesTest {

    private val now = Instant.parse("2026-01-01T00:00:00Z")

    private fun newInMemoryDatabase(): SkywardDatabase {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        SkywardDatabase.Schema.create(driver)
        return SkywardDatabase(driver)
    }

    @Test
    fun locationRepoUpsertAndReadRoundTrips() = runTest {
        val db = newInMemoryDatabase()
        val repo = LocationRepo(db)
        val home = SavedLocation(id = "home", name = "Home", point = GeoPoint(48.1351, 11.5820), isPrimary = true, createdAt = now, modifiedAt = now)
        val cabin = SavedLocation(id = "cabin", name = "Cabin", point = GeoPoint(47.0, 11.0), isPrimary = false, createdAt = now, modifiedAt = now)

        repo.upsert(home)
        repo.upsert(cabin)

        assertEquals(listOf(home, cabin), repo.observeAll().first())
        assertEquals(home, repo.getById("home"))
    }

    @Test
    fun locationRepoUpsertClearsPreviousPrimary() = runTest {
        val db = newInMemoryDatabase()
        val repo = LocationRepo(db)
        val home = SavedLocation(id = "home", name = "Home", point = GeoPoint(48.1351, 11.5820), isPrimary = true, createdAt = now, modifiedAt = now)
        repo.upsert(home)

        val cabin = SavedLocation(id = "cabin", name = "Cabin", point = GeoPoint(47.0, 11.0), isPrimary = true, createdAt = now, modifiedAt = now)
        repo.upsert(cabin)

        assertEquals(false, repo.getById("home")?.isPrimary, "only one location may be primary at a time")
        assertEquals(true, repo.getById("cabin")?.isPrimary)
    }

    @Test
    fun occurrenceRepoUpsertPreservesExplicitFirstSeenAtAndRoundTripsPayload() = runTest {
        val db = newInMemoryDatabase()
        val repo = OccurrenceRepo(db)
        val occ = Occurrence(
            id = "se:20260812", phenomenon = Phenomenon.SOLAR_ECLIPSE, sourceId = "eclipse", title = "Total solar eclipse",
            window = TimeWindow(now, now + 3.hours), peakTime = now + 1.hours, certainty = Certainty.CERTAIN,
            payload = SolarEclipsePayload(SolarEclipseKind.TOTAL, GeoPoint(0.0, 0.0), now + 1.hours, emptyList(), 1.0),
            fetchedAt = now, expiresAt = null,
        )
        val firstSeen = now - 30.days

        repo.upsert(occ, firstSeenAt = firstSeen)

        assertEquals(occ, repo.getById("se:20260812"))
        assertEquals(firstSeen, repo.getFirstSeenAt("se:20260812"))
        assertEquals(setOf("se:20260812"), repo.getIdsBySource("eclipse"))
        assertEquals(listOf(occ), repo.getBySource("eclipse"))
    }

    @Test
    fun ruleRepoUpsertRoundTripsConditionAndScheduleAndHonorsVisibility() = runTest {
        val db = newInMemoryDatabase()
        val repo = RuleRepo(db)
        val visible = Rule(
            id = "r1", name = "Visible rule", enabled = true, phenomena = setOf(Phenomenon.SOLAR_ECLIPSE),
            locationIds = listOf("home"), condition = Cond.And(listOf(Cond.VisibleAtLocation(Quality.GOOD), Cond.KpAtLeast(5.0))),
            schedule = NotifySchedule(listOf(1.days, 2.hours), Anchor.PEAK, notifyOnFirstSeen = false, quietHours = null),
            hidden = false, createdAt = now, modifiedAt = now,
        )
        val hidden = visible.copy(id = "r2", name = "Mute", hidden = true, condition = Cond.OccurrenceIdIs("se:20260812"))

        repo.upsert(visible)
        repo.upsert(hidden)

        assertEquals(visible, repo.getById("r1"))
        assertEquals(hidden, repo.getById("r2"))
        assertEquals(listOf(visible), repo.observeVisible().first())
        assertEquals(listOf(visible, hidden), repo.observeAll().first())
        // getEnabled() must NOT exclude hidden rules -- a mute/one-off-reminder
        // rule (hidden=true) is still enabled and must be evaluated (§13.3's
        // own framing: "included in evaluation & sync"), just left out of the
        // Rules list UI (that's what observeVisible() is for).
        assertEquals(listOf(visible, hidden), repo.getEnabled())
    }

    @Test
    fun ruleRepoSetEnabledUpdatesEnabledAndModifiedAt() = runTest {
        val db = newInMemoryDatabase()
        val repo = RuleRepo(db)
        val rule = Rule(
            id = "r1", name = "n", enabled = true, phenomena = setOf(Phenomenon.SOLAR_ECLIPSE), locationIds = null,
            condition = Cond.VisibleAtLocation(), schedule = NotifySchedule(emptyList(), Anchor.PEAK, false, null),
            createdAt = now, modifiedAt = now,
        )
        repo.upsert(rule)

        val later = now + 1.hours
        repo.setEnabled("r1", enabled = false, modifiedAt = later)

        val updated = repo.getById("r1")
        assertEquals(false, updated?.enabled)
        assertEquals(later, updated?.modifiedAt)
    }

    @Test
    fun notificationRepoUpsertReadAndStatusUpdatesRoundTrip() = runTest {
        val db = newInMemoryDatabase()
        val repo = NotificationRepo(db)
        val n = PlannedNotification(
            id = "se:20260812|123|7200", occurrenceId = "se:20260812", ruleId = "r1", locationId = "home",
            fireAt = now + 1.hours, status = NotificationStatus.PENDING, precision = Precision.EXACT,
            title = "t", body = "b", createdAt = now, firedAt = null,
        )

        repo.upsert(n)
        assertEquals(n, repo.getById(n.id))
        assertEquals(listOf(n), repo.getByOccurrence("se:20260812"))
        assertEquals(listOf(n), repo.getPendingDue(now + 2.hours))
        assertTrue(repo.getPendingDue(now).isEmpty(), "not yet due")

        // A fireAt with a sub-second component: Instant.toString() would render this without a
        // trailing zero-padded fraction (or none at all for whole seconds), which breaks
        // lexicographic ("<=") comparison against other rows once stored as SQL TEXT.
        val fractional = PlannedNotification(
            id = "se:20260812|456|60", occurrenceId = "se:20260812", ruleId = "r1", locationId = "home",
            fireAt = now + 1.hours + 250.milliseconds, status = NotificationStatus.PENDING, precision = Precision.EXACT,
            title = "t2", body = "b2", createdAt = now, firedAt = null,
        )
        repo.upsert(fractional)
        assertEquals(setOf(n.id, fractional.id), repo.getPendingDue(now + 2.hours).map { it.id }.toSet(), "both due at the same boundary instant")
        assertTrue(repo.getPendingDue(now + 1.hours).none { it.id == fractional.id }, "not yet due one instant before its fireAt")

        repo.updatePrecision(n.id, Precision.APPROXIMATE)
        assertEquals(Precision.APPROXIMATE, repo.getById(n.id)?.precision)

        repo.updateStatus(n.id, NotificationStatus.FIRED, now + 1.hours)
        val fired = repo.getById(n.id)
        assertEquals(NotificationStatus.FIRED, fired?.status)
        assertEquals(now + 1.hours, fired?.firedAt)

        repo.deleteById(n.id)
        assertNull(repo.getById(n.id))
    }

    @Test
    fun sourceStateRepoUpsertAndReadRoundTrips() = runTest {
        val db = newInMemoryDatabase()
        val repo = SourceStateRepo(db)
        repo.upsert("eclipse", "next_run_at", "2026-01-02T00:00:00Z".encodeToByteArray(), now)

        assertEquals("2026-01-02T00:00:00Z", repo.getValue("eclipse", "next_run_at")?.decodeToString())
        assertEquals(1, repo.getBySource("eclipse").size)

        repo.delete("eclipse", "next_run_at")
        assertNull(repo.getValue("eclipse", "next_run_at"))
    }

    @Test
    fun settingsRepoGenericAndTypedAccessorsRoundTrip() = runTest {
        val db = newInMemoryDatabase()
        val repo = SettingsRepo(db)

        assertEquals(3, repo.getHorizonYears(), "default horizon is 3 years when unset")
        repo.setHorizonYears(5)
        assertEquals(5, repo.getHorizonYears())

        assertEquals(false, repo.observeOnboardingDone().first())
        repo.setOnboardingDone(true)
        assertEquals(true, repo.observeOnboardingDone().first())

        assertEquals(true, repo.isSourceEnabled("swpc"), "sources default enabled")
        repo.setSourceEnabled("swpc", false)
        assertEquals(false, repo.isSourceEnabled("swpc"))

        assertEquals(null, repo.getExactAlarmCardDismissedVersion())
        repo.setExactAlarmCardDismissedVersion(123L)
        assertEquals(123L, repo.getExactAlarmCardDismissedVersion())

        // §13's "dark theme default-follows-system" rests on an unset theme
        // reading back as SYSTEM rather than LIGHT.
        assertEquals(ThemeChoice.SYSTEM, repo.observeTheme().first(), "theme defaults to following the system")
        repo.setTheme(ThemeChoice.DARK)
        assertEquals(ThemeChoice.DARK, repo.observeTheme().first())

        repo.set("custom", "value")
        assertEquals("value", repo.get("custom"))
        assertEquals(
            mapOf(
                "horizon_years" to "5",
                "onboarding_done" to "true",
                "source.swpc.enabled" to "false",
                "exact_alarm_card_dismissed_version" to "123",
                // Pins the raw key and value, not just the typed pair above:
                // the desktop settings screen reads `settings["theme"]`
                // straight out of this map and §12's sync file ships it
                // verbatim to the other platform, so a rename would break both
                // while the typed round trip still passed.
                "theme" to "DARK",
                "custom" to "value",
            ),
            repo.observeAll().first(),
        )
    }
}
