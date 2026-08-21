package dev.fritze.skyward.core.planner

import dev.fritze.skyward.core.model.Certainty
import dev.fritze.skyward.core.model.GeoPoint
import dev.fritze.skyward.core.model.LocalDetails
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
import dev.fritze.skyward.core.model.VisibilityResult
import dev.fritze.skyward.core.rules.Anchor
import dev.fritze.skyward.core.rules.Cond
import dev.fritze.skyward.core.rules.NotifySchedule
import dev.fritze.skyward.core.rules.QuietHours
import dev.fritze.skyward.core.rules.Rule
import dev.fritze.skyward.core.rules.sendsNoReminders
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

class PlannerTest {

    private val utc = TimeZone.UTC
    private val now = Instant.parse("2026-01-01T00:00:00Z")
    private val peak = Instant.parse("2026-02-01T12:00:00Z")

    private fun loc(id: String, name: String) = SavedLocation(
        id = id, name = name, point = GeoPoint(52.0, 7.6), isPrimary = id == "home",
        createdAt = now, modifiedAt = now,
    )

    private fun rule(id: String, leads: List<kotlin.time.Duration> = listOf(1.days), quietHours: QuietHours? = null, notifyOnFirstSeen: Boolean = false) = Rule(
        id = id, name = id, enabled = true, phenomena = setOf(Phenomenon.SOLAR_ECLIPSE),
        locationIds = null, condition = Cond.VisibleAtLocation(Quality.MARGINAL),
        schedule = NotifySchedule(leads, Anchor.PEAK, notifyOnFirstSeen, quietHours),
        createdAt = now, modifiedAt = now,
    )

    private fun occ(id: String = "se:test", peakTime: Instant = peak, windowEnd: Instant = peakTime + 3.hours, certainty: Certainty = Certainty.CERTAIN) = Occurrence(
        id = id, phenomenon = Phenomenon.SOLAR_ECLIPSE, sourceId = "eclipse", title = "Eclipse",
        window = TimeWindow(peakTime - 3.hours, windowEnd), peakTime = peakTime, certainty = certainty,
        payload = SolarEclipsePayload(SolarEclipseKind.TOTAL, GeoPoint(0.0, 0.0), peakTime, emptyList(), 1.0),
        fetchedAt = now, expiresAt = null,
    )

    private fun visres(quality: Quality) = VisibilityResult(
        visibleAtLocation = quality != Quality.NONE, quality = quality, localDetails = null,
        nearestVisiblePoint = null, travelDistanceKm = null, travelBearingDeg = null, qualityAtNearestPoint = null,
    )

    @Test
    fun dedupsAcrossTwoRulesAndTwoLocationsKeepingTheBestQualityLocation() {
        val home = loc("home", "Home")
        val cabin = loc("cabin", "Cabin")
        val ruleA = rule("ruleA")
        val ruleB = rule("ruleB")
        val theOcc = occ()

        val matches = listOf(
            Match(ruleA, theOcc, home, visres(Quality.GOOD)),
            Match(ruleB, theOcc, home, visres(Quality.GOOD)),
            Match(ruleA, theOcc, cabin, visres(Quality.EXCELLENT)),
        )

        val desired = Planner.desiredNotifications(matches, now, utc)

        // All three matches share the same (occId, anchorTime, lead) key.
        assertEquals(1, desired.size)
        val notification = desired.first()
        assertEquals(ruleA.id, notification.ruleId, "expected the first matching rule")
        assertEquals(cabin.id, notification.locationId, "expected the best-quality location")
    }

    @Test
    fun aLeadComputedInThePastIsDropped() {
        val home = loc("home", "Home")
        // Peak already passed relative to `now`; every lead's fireAt is in the past.
        val pastOcc = occ(peakTime = now - 1.hours, windowEnd = now + 1.hours)
        val matches = listOf(Match(rule("r", leads = listOf(1.days, 2.hours)), pastOcc, home, visres(Quality.GOOD)))

        val desired = Planner.desiredNotifications(matches, now, utc)

        assertTrue(desired.isEmpty(), "expected all leads to be dropped as computed-in-the-past")
    }

    @Test
    fun notifyOnFirstSeenFiresAtDiscoveryTimeButKeysOnPeakTimeIndependentOfScheduleAnchor() {
        val home = loc("home", "Home")
        val r = rule("r", leads = emptyList(), notifyOnFirstSeen = true)
        val matches = listOf(Match(r, occ(), home, visres(Quality.GOOD)))

        val desired = Planner.desiredNotifications(matches, now, utc)

        assertEquals(1, desired.size)
        // "fire as soon as occurrence first matches" (§9.6) -- discovery
        // time, not the (possibly-distant) peak.
        assertEquals(now, desired.first().fireAt)
        // The dedup key still anchors on peakTime (not `now`), so exactly
        // one first-seen notification is ever produced per occurrence.
        assertEquals("se:test|${peak.epochSeconds}|first", desired.first().id)
    }

    /**
     * #73: the state the rule editor could silently save. Worth a planner
     * test rather than only a UI one — `sendsNoReminders` is a claim about
     * what §9.2 will produce, and this is where that claim is true or false.
     */
    @Test
    fun aScheduleWithNoLeadsAndNoFirstSeenPlansNothing() {
        val home = loc("home", "Home")
        val silent = rule("silent", leads = emptyList(), notifyOnFirstSeen = false)
        assertTrue(silent.schedule.sendsNoReminders)

        val matches = listOf(Match(silent, occ(), home, visres(Quality.EXCELLENT)))

        // The rule matches — an excellent, visible, future eclipse — and still
        // nothing is planned.
        assertTrue(Planner.desiredNotifications(matches, now, utc).isEmpty())
    }

    @Test
    fun aScheduleWithEitherTriggerIsNotSilent() {
        assertFalse(rule("lead", leads = listOf(1.days)).schedule.sendsNoReminders)
        assertFalse(rule("first", leads = emptyList(), notifyOnFirstSeen = true).schedule.sendsNoReminders)
    }

    @Test
    fun quietHoursDefersALeadIntoTheQuietWindow() {
        val home = loc("home", "Home")
        // Lead lands inside 00:00-06:00 quiet hours.
        val quietPeak = Instant.parse("2026-02-01T02:30:00Z")
        val r = rule("r", leads = listOf(kotlin.time.Duration.ZERO), quietHours = QuietHours(0, 6))
        val matches = listOf(Match(r, occ(peakTime = quietPeak, windowEnd = quietPeak + 10.hours), home, visres(Quality.GOOD)))

        val desired = Planner.desiredNotifications(matches, now, utc)

        assertEquals(1, desired.size)
        val fireAt = desired.first().fireAt
        assertEquals(Instant.parse("2026-02-01T06:00:00Z"), fireAt, "expected deferral to the end of the quiet window")
    }

    @Test
    fun quietHoursDeferralIsDstSafe() {
        val nyZone = TimeZone.of("America/New_York")
        val home = loc("home", "Home")
        // 2026-03-08T06:00:00Z = 01:00 EST (America/New_York, before that
        // day's 02:00-local spring-forward transition) -- inside the
        // 00:00-06:00 local quiet window. The deferred target, 06:00 local
        // *that same day*, falls after the transition (EDT, UTC-4): 10:00Z,
        // not 11:00Z -- the wrong answer elapsed-minutes-from-midnight
        // arithmetic would give by ignoring the hour DST skips.
        val rawFireAt = Instant.parse("2026-03-08T06:00:00Z")
        val r = rule("r", leads = listOf(kotlin.time.Duration.ZERO), quietHours = QuietHours(0, 6))
        val matches = listOf(Match(r, occ(peakTime = rawFireAt, windowEnd = rawFireAt + 12.hours), home, visres(Quality.GOOD)))

        val desired = Planner.desiredNotifications(matches, now, nyZone)

        assertEquals(1, desired.size)
        assertEquals(Instant.parse("2026-03-08T10:00:00Z"), desired.first().fireAt)
    }

    @Test
    fun quietHoursDropsAForecastNotificationThatWouldDeferPastTheWindow() {
        val home = loc("home", "Home")
        val quietPeak = Instant.parse("2026-02-01T02:30:00Z")
        val r = rule("r", leads = listOf(kotlin.time.Duration.ZERO), quietHours = QuietHours(0, 6))
        // Window ends at 03:00 -- before the 06:00 deferred fire time.
        val forecastOcc = occ(peakTime = quietPeak, windowEnd = Instant.parse("2026-02-01T03:00:00Z"), certainty = Certainty.FORECAST)
        val matches = listOf(Match(r, forecastOcc, home, visres(Quality.GOOD)))

        val desired = Planner.desiredNotifications(matches, now, utc)

        assertTrue(desired.isEmpty(), "expected the FORECAST notification to be dropped, not deferred past its window")
    }

    // ---- reconciliation ----

    private fun plannedFrom(desired: PlannedNotification, status: NotificationStatus, firedAt: Instant? = null) =
        desired.copy(status = status, firedAt = firedAt)

    @Test
    fun occurrenceNoLongerPresentCancelsItsPendingNotification() {
        val home = loc("home", "Home")
        val theOcc = occ()
        val previous = Planner.desiredNotifications(listOf(Match(rule("r"), theOcc, home, visres(Quality.GOOD))), now, utc)

        // The occurrence has since disappeared (e.g. re-fetch no longer returns it).
        val result = Planner.reconcile(previous, emptyList(), now, emptyMap())

        assertEquals(1, result.size)
        assertEquals(NotificationStatus.CANCELLED, result.first().status)
    }

    @Test
    fun disablingTheRuleRemovesItsMatchAndCancelsThePendingNotification() {
        val home = loc("home", "Home")
        val theOcc = occ()
        val enabledRule = rule("r")
        val matchesEnabled = listOf(Match(enabledRule, theOcc, home, visres(Quality.GOOD)))
        val previous = Planner.desiredNotifications(matchesEnabled, now, utc)

        // Simulate disabling: computeMatches would no longer produce this match at all.
        val desiredAfterDisable = Planner.desiredNotifications(emptyList(), now, utc)
        val result = Planner.reconcile(previous, desiredAfterDisable, now, mapOf(theOcc.id to theOcc))

        assertEquals(1, result.size)
        assertEquals(NotificationStatus.CANCELLED, result.first().status)
    }

    @Test
    fun aFiredNotificationIsPreservedAsHistoryEvenWhenNoLongerDesired() {
        val home = loc("home", "Home")
        val theOcc = occ()
        val desiredOnce = Planner.desiredNotifications(listOf(Match(rule("r"), theOcc, home, visres(Quality.GOOD))), now, utc)
        val fired = listOf(plannedFrom(desiredOnce.first(), NotificationStatus.FIRED, firedAt = now))

        val result = Planner.reconcile(fired, emptyList(), now, emptyMap())

        assertEquals(1, result.size)
        assertEquals(NotificationStatus.FIRED, result.first().status)
        assertEquals(now, result.first().firedAt)
    }

    @Test
    fun deviceOffMissedWindowFiresImmediatelyIfStillWithinWindowElseMissed() {
        val home = loc("home", "Home")
        val laterNow = Instant.parse("2026-02-01T13:00:00Z") // after peak (12:00), window ends at 15:00
        val theOcc = occ(windowEnd = Instant.parse("2026-02-01T15:00:00Z"))
        val overdue = PlannedNotification(
            id = "se:test|${peak.epochSeconds}|86400",
            occurrenceId = theOcc.id, ruleId = "r", locationId = home.id,
            fireAt = Instant.parse("2026-01-31T12:00:00Z"), // this lead's fire time already passed
            status = NotificationStatus.PENDING, precision = Precision.EXACT,
            title = "t", body = "b", createdAt = now, firedAt = null,
        )
        val stillDesired = listOf(overdue.copy(fireAt = Instant.parse("2026-01-31T12:00:00Z")))

        val caughtUp = Planner.reconcile(listOf(overdue), stillDesired, laterNow, mapOf(theOcc.id to theOcc))
        assertEquals(NotificationStatus.PENDING, caughtUp.first().status)
        assertEquals(laterNow, caughtUp.first().fireAt, "expected immediate catch-up fire time")

        val pastWindowNow = Instant.parse("2026-02-01T16:00:00Z") // after window end
        val missed = Planner.reconcile(listOf(overdue), stillDesired, pastWindowNow, mapOf(theOcc.id to theOcc))
        assertEquals(NotificationStatus.MISSED, missed.first().status)
    }

    @Test
    fun reconcileUpdatesFireAtWhenTheSameKeyResolvesToADifferentTimeWithoutChangingStatus() {
        // The dedup key doesn't encode `fireAt` -- if a re-plan resolves the
        // same key to a different time (e.g. the device timezone changed,
        // shifting a quiet-hours deferral), reconcile must still propagate
        // the new time into an already-PENDING row rather than freezing it.
        val home = loc("home", "Home")
        val theOcc = occ()
        val matches = listOf(Match(rule("r"), theOcc, home, visres(Quality.GOOD)))
        val previous = Planner.desiredNotifications(matches, now, utc)
        val shifted = previous.map { it.copy(fireAt = it.fireAt + 1.hours) }

        val result = Planner.reconcile(previous, shifted, now, mapOf(theOcc.id to theOcc))

        assertEquals(1, result.size)
        assertEquals(NotificationStatus.PENDING, result.first().status)
        assertEquals(shifted.first().fireAt, result.first().fireAt)
    }

    @Test
    fun repeatedPlanningWithUnchangedInputsProducesIdenticalDesiredSets() {
        // Regression analog for "two consecutive comet refreshes 30 days
        // apart produce identical peakMagDate and therefore zero re-plans"
        // (§17.4): as long as the underlying occurrence/rule/location set
        // is unchanged, desiredNotifications() must be byte-identical
        // (same ids, same fireAt) run after run — the input to the
        // determinism guard (§17.6).
        val home = loc("home", "Home")
        val theOcc = occ()
        val matches = listOf(Match(rule("r"), theOcc, home, visres(Quality.GOOD)))

        val firstRun = Planner.desiredNotifications(matches, now, utc)
        val secondRun = Planner.desiredNotifications(matches, now, utc)

        assertEquals(firstRun, secondRun)

        val previous = firstRun
        val reconciled = Planner.reconcile(previous, secondRun, now, mapOf(theOcc.id to theOcc))
        assertEquals(previous, reconciled, "unchanged inputs must produce zero-diff reconciliation")
    }

    @Test
    fun bestViewingFallsBackToPeakForPayloadsWithoutABestViewingWindow() {
        // Solar eclipses have no MeteorLocal/CometLocal localDetails.
        val home = loc("home", "Home")
        val r = rule("r", leads = listOf(kotlin.time.Duration.ZERO)).copy(
            schedule = NotifySchedule(listOf(kotlin.time.Duration.ZERO), Anchor.BEST_VIEWING, false, null),
        )
        val matches = listOf(Match(r, occ(), home, visres(Quality.GOOD)))

        val desired = Planner.desiredNotifications(matches, now, utc)

        assertEquals(1, desired.size)
        assertEquals(peak, desired.first().fireAt)
    }
}
