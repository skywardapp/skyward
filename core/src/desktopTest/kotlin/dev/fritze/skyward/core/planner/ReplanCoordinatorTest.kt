package dev.fritze.skyward.core.planner

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
import dev.fritze.skyward.core.persistence.LocationRepo
import dev.fritze.skyward.core.persistence.NotificationRepo
import dev.fritze.skyward.core.persistence.OccurrenceRepo
import dev.fritze.skyward.core.persistence.RuleRepo
import dev.fritze.skyward.core.persistence.SkywardDatabase
import dev.fritze.skyward.core.rules.Anchor
import dev.fritze.skyward.core.rules.Cond
import dev.fritze.skyward.core.rules.NotifySchedule
import dev.fritze.skyward.core.rules.Rule
import dev.fritze.skyward.core.visibility.VisibilityContext
import dev.fritze.skyward.core.visibility.VisibilityModel
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/** §9.7: SourceRunner.runDue -> Planner.replan()'s DB-backed orchestration, and §13.3's mute suppression. */
class ReplanCoordinatorTest {

    private val now = Instant.parse("2026-01-01T00:00:00Z")
    private val utc = TimeZone.UTC

    private class AlwaysGoodVisibilityModel : VisibilityModel {
        override val phenomenon = Phenomenon.SOLAR_ECLIPSE
        override fun evaluate(occ: Occurrence, loc: SavedLocation, ctx: VisibilityContext) =
            dev.fritze.skyward.core.model.VisibilityResult(true, Quality.GOOD, null, null, null, null, null)
    }

    private class Fixture {
        val db: SkywardDatabase
        val occurrenceRepo: OccurrenceRepo
        val locationRepo: LocationRepo
        val ruleRepo: RuleRepo
        val notificationRepo: NotificationRepo
        val coordinator: ReplanCoordinator

        init {
            val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            SkywardDatabase.Schema.create(driver)
            db = SkywardDatabase(driver)
            occurrenceRepo = OccurrenceRepo(db)
            locationRepo = LocationRepo(db)
            ruleRepo = RuleRepo(db)
            notificationRepo = NotificationRepo(db)
            coordinator = ReplanCoordinator(
                occurrenceRepo, locationRepo, ruleRepo, notificationRepo,
                mapOf(Phenomenon.SOLAR_ECLIPSE to AlwaysGoodVisibilityModel()),
            )
        }
    }

    private fun eclipseOcc(id: String, peakTime: Instant) = Occurrence(
        id = id, phenomenon = Phenomenon.SOLAR_ECLIPSE, sourceId = "eclipse", title = "Eclipse",
        window = TimeWindow(peakTime - 3.hours, peakTime + 3.hours), peakTime = peakTime, certainty = Certainty.CERTAIN,
        payload = SolarEclipsePayload(SolarEclipseKind.TOTAL, GeoPoint(0.0, 0.0), peakTime, emptyList(), 1.0),
        fetchedAt = now, expiresAt = null,
    )

    private fun visibleRule(id: String = "r", hidden: Boolean = false, condition: Cond = Cond.VisibleAtLocation(Quality.MARGINAL)) = Rule(
        id = id, name = id, enabled = true, phenomena = setOf(Phenomenon.SOLAR_ECLIPSE), locationIds = null,
        condition = condition, schedule = NotifySchedule(listOf(1.days), Anchor.PEAK, notifyOnFirstSeen = false, quietHours = null),
        hidden = hidden, createdAt = now, modifiedAt = now,
    )

    @Test
    fun replanPersistsDesiredNotificationsFromDbState() = runTest {
        val fx = Fixture()
        val home = SavedLocation(id = "home", name = "Home", point = GeoPoint(48.0, 0.0), isPrimary = true, createdAt = now, modifiedAt = now)
        fx.locationRepo.upsert(home)
        fx.occurrenceRepo.upsert(eclipseOcc("se:1", now + 30.days), firstSeenAt = now)
        fx.ruleRepo.upsert(visibleRule())

        val reconciled = fx.coordinator.replan(now, utc)

        assertEquals(1, reconciled.size)
        assertEquals(NotificationStatus.PENDING, reconciled.first().status)
        assertEquals(1, fx.notificationRepo.getAll().size, "replan must persist, not just return")
        assertTrue(reconciled.first().body.isNotBlank(), "body should be real §10.5 copy, not a placeholder")
    }

    @Test
    fun secondReplanCancelsNotificationsForADeletedOccurrence() = runTest {
        val fx = Fixture()
        val home = SavedLocation(id = "home", name = "Home", point = GeoPoint(48.0, 0.0), isPrimary = true, createdAt = now, modifiedAt = now)
        fx.locationRepo.upsert(home)
        fx.occurrenceRepo.upsert(eclipseOcc("se:1", now + 30.days), firstSeenAt = now)
        fx.ruleRepo.upsert(visibleRule())
        fx.coordinator.replan(now, utc)

        fx.occurrenceRepo.deleteById("se:1")
        val reconciled = fx.coordinator.replan(now + 1.hours, utc)

        assertEquals(1, reconciled.size)
        assertEquals(NotificationStatus.CANCELLED, reconciled.first().status)
    }

    @Test
    fun aMuteSuppressorDropsNotificationsForItsOccurrenceOnly() = runTest {
        val fx = Fixture()
        val home = SavedLocation(id = "home", name = "Home", point = GeoPoint(48.0, 0.0), isPrimary = true, createdAt = now, modifiedAt = now)
        fx.locationRepo.upsert(home)
        fx.occurrenceRepo.upsert(eclipseOcc("se:muted", now + 30.days), firstSeenAt = now)
        fx.occurrenceRepo.upsert(eclipseOcc("se:normal", now + 40.days), firstSeenAt = now)
        fx.ruleRepo.upsert(visibleRule("normal-rule"))
        // §13.3's exact "mute this event" recipe: hidden, OccurrenceIdIs, empty schedule.
        fx.ruleRepo.upsert(
            visibleRule("mute-se:muted", hidden = true, condition = Cond.OccurrenceIdIs("se:muted"))
                .copy(schedule = NotifySchedule(emptyList(), Anchor.PEAK, notifyOnFirstSeen = false, quietHours = null)),
        )

        val reconciled = fx.coordinator.replan(now, utc)

        assertEquals(1, reconciled.size)
        assertEquals("se:normal", reconciled.first().occurrenceId)
    }

    @Test
    fun aHiddenOneOffReminderIsNotTreatedAsAMuteSuppressor() = runTest {
        val fx = Fixture()
        val home = SavedLocation(id = "home", name = "Home", point = GeoPoint(48.0, 0.0), isPrimary = true, createdAt = now, modifiedAt = now)
        fx.locationRepo.upsert(home)
        fx.occurrenceRepo.upsert(eclipseOcc("se:1", now + 30.days), firstSeenAt = now)
        // §13.3's "add one-off extra reminder" recipe: hidden, OccurrenceIdIs, but a real lead -- not a mute.
        fx.ruleRepo.upsert(
            visibleRule("extra-se:1", hidden = true, condition = Cond.OccurrenceIdIs("se:1"))
                .copy(schedule = NotifySchedule(listOf(3.days), Anchor.PEAK, notifyOnFirstSeen = false, quietHours = null)),
        )

        val reconciled = fx.coordinator.replan(now, utc)

        assertEquals(1, reconciled.size, "a hidden rule with a real lead must still produce a notification")
        assertEquals("se:1", reconciled.first().occurrenceId)
    }

    @Test
    fun aOneOffExtraReminderDoesNotCollideWithTheRuleGeneratedLeadsForTheSameOccurrence() = runTest {
        val fx = Fixture()
        val home = SavedLocation(id = "home", name = "Home", point = GeoPoint(48.0, 0.0), isPrimary = true, createdAt = now, modifiedAt = now)
        fx.locationRepo.upsert(home)
        fx.occurrenceRepo.upsert(eclipseOcc("se:1", now + 30.days), firstSeenAt = now)
        // The ordinary rule plans a 1-day lead (visibleRule's default schedule);
        // the one-off extra reminder picks a different lead, per §13.3.
        fx.ruleRepo.upsert(visibleRule("normal-rule"))
        fx.ruleRepo.upsert(
            visibleRule("extra-se:1", hidden = true, condition = Cond.OccurrenceIdIs("se:1"))
                .copy(schedule = NotifySchedule(listOf(3.days), Anchor.PEAK, notifyOnFirstSeen = false, quietHours = null)),
        )

        val reconciled = fx.coordinator.replan(now, utc)

        assertEquals(2, reconciled.size, "the rule-generated lead and the one-off extra reminder must plan as separate notifications")
        val ids = reconciled.map { it.id }.toSet()
        assertEquals(2, ids.size, "dedup keys (§10.4) must not collide")
    }

    @Test
    fun replanPrunesFiredHistoryOlderThan180DaysButKeepsEverythingElse() = runTest {
        val fx = Fixture()
        fx.notificationRepo.upsert(fired("old-fired", firedAt = now - 181.days))
        fx.notificationRepo.upsert(fired("recent-fired", firedAt = now - 179.days))
        fx.notificationRepo.upsert(pending("ancient-pending", fireAt = now - 400.days))

        fx.coordinator.replan(now, utc)

        val remainingIds = fx.notificationRepo.getAll().mapTo(mutableSetOf()) { it.id }
        assertTrue("old-fired" !in remainingIds, "a FIRED row older than 180 days must be pruned")
        assertTrue("recent-fired" in remainingIds, "a FIRED row inside the 180-day window must be kept")
        assertTrue("ancient-pending" in remainingIds, "non-FIRED rows must be kept regardless of age")
    }

    private fun fired(id: String, firedAt: Instant) = PlannedNotification(
        id = id, occurrenceId = "occ-$id", ruleId = "r", locationId = "home",
        fireAt = firedAt, status = NotificationStatus.FIRED, precision = Precision.EXACT,
        title = "t", body = "b", createdAt = firedAt, firedAt = firedAt,
    )

    private fun pending(id: String, fireAt: Instant) = PlannedNotification(
        id = id, occurrenceId = "occ-$id", ruleId = "r", locationId = "home",
        fireAt = fireAt, status = NotificationStatus.PENDING, precision = Precision.EXACT,
        title = "t", body = "b", createdAt = fireAt, firedAt = null,
    )
}
