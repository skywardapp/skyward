package dev.fritze.skyward.core.planner

import dev.fritze.skyward.core.model.Certainty
import dev.fritze.skyward.core.model.GeoPoint
import dev.fritze.skyward.core.model.LocalDetails
import dev.fritze.skyward.core.model.MeteorShowerPayload
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
import dev.fritze.skyward.core.visibility.VisibilityContext
import dev.fritze.skyward.core.visibility.VisibilityModel
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class PlannerTest {

    private val utc = TimeZone.UTC
    private val now = Instant.parse("2026-01-01T00:00:00Z")
    private val peak = Instant.parse("2026-02-01T12:00:00Z")

    private fun loc(id: String, name: String) = SavedLocation(
        id = id, name = name, point = GeoPoint(52.0, 7.6), isPrimary = id == "home",
        createdAt = now, modifiedAt = now,
    )

    private fun rule(
        id: String,
        leads: List<kotlin.time.Duration> = listOf(1.days),
        quietHours: QuietHours? = null,
        notifyOnFirstSeen: Boolean = false,
        phenomenon: Phenomenon = Phenomenon.SOLAR_ECLIPSE,
        anchor: Anchor = Anchor.PEAK,
        firstSeenCooldown: kotlin.time.Duration? = null,
    ) = Rule(
        id = id, name = id, enabled = true, phenomena = setOf(phenomenon),
        locationIds = null, condition = Cond.VisibleAtLocation(Quality.MARGINAL),
        schedule = NotifySchedule(leads, anchor, notifyOnFirstSeen, quietHours, firstSeenCooldown),
        createdAt = now, modifiedAt = now,
    )

    private fun occ(
        id: String = "se:test",
        peakTime: Instant = peak,
        windowEnd: Instant = peakTime + 3.hours,
        certainty: Certainty = Certainty.CERTAIN,
        expiresAt: Instant? = null,
    ) = Occurrence(
        id = id, phenomenon = Phenomenon.SOLAR_ECLIPSE, sourceId = "eclipse", title = "Eclipse",
        window = TimeWindow(peakTime - 3.hours, windowEnd), peakTime = peakTime, certainty = certainty,
        payload = SolarEclipsePayload(SolarEclipseKind.TOTAL, GeoPoint(0.0, 0.0), peakTime, emptyList(), 1.0),
        fetchedAt = now, expiresAt = expiresAt,
    )

    /** Stands in for a real visibility model in [Planner.computeMatches] tests. */
    private class AlwaysVisibleModel : VisibilityModel {
        override val phenomenon = Phenomenon.SOLAR_ECLIPSE
        override fun evaluate(occ: Occurrence, loc: SavedLocation, ctx: VisibilityContext) =
            VisibilityResult(true, Quality.EXCELLENT, null, null, null, null, null)
    }

    private fun visres(quality: Quality) = VisibilityResult(
        visibleAtLocation = quality != Quality.NONE, quality = quality, localDetails = null,
        nearestVisiblePoint = null, travelDistanceKm = null, travelBearingDeg = null, qualityAtNearestPoint = null,
    )

    /** §7.2 shower occurrence, for the BEST_VIEWING anchor cases. */
    private fun showerOcc(peakTime: Instant = peak) = Occurrence(
        id = "ms:2026:PER", phenomenon = Phenomenon.METEOR_SHOWER, sourceId = "showers", title = "Perseids",
        window = TimeWindow(peakTime - 2.days, peakTime + 2.days), peakTime = peakTime, certainty = Certainty.CERTAIN,
        payload = MeteorShowerPayload(
            iauCode = "PER", name = "Perseids", zhr = 100, zhrNote = null,
            radiantRaDeg = 46.2, radiantDecDeg = 57.4, speedKmS = 59.0, parentBody = "109P/Swift-Tuttle",
            activityStart = peakTime - 20.days, activityEnd = peakTime + 12.days, moonIlluminationAtPeak = 0.05,
        ),
        fetchedAt = now, expiresAt = null,
    )

    private fun showerVisres(quality: Quality, bestViewingStart: Instant) = VisibilityResult(
        visibleAtLocation = quality != Quality.NONE, quality = quality,
        localDetails = LocalDetails.MeteorLocal(
            bestViewingStart = bestViewingStart,
            bestViewingEnd = bestViewingStart + 5.hours,
            maxRadiantAltDeg = 62.0,
            moonIllumination = 0.05,
            moonUpDuringBest = false,
        ),
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
    fun anExpiredForecastOccurrenceMatchesNoRules() {
        // §5: the last OVATION nowcast and its 3-hour slots stay in the DB
        // while SWPC is unreachable -- SourceRunner only withdraws
        // occurrences on a *successful* refresh (§6.2 backs off up to 24 h).
        // Expiry is the only thing standing between hours-old forecast data
        // and a notification presenting it as current.
        val home = loc("home", "Home")
        val fresh = occ(id = "se:fresh", certainty = Certainty.FORECAST, expiresAt = now + 2.hours)
        val expired = occ(id = "se:expired", certainty = Certainty.FORECAST, expiresAt = now - 1.hours)
        // Expiry is inclusive: at exactly `expiresAt` the row is no longer current.
        val expiringExactlyNow = occ(id = "se:on-the-boundary", certainty = Certainty.FORECAST, expiresAt = now)

        val matches = Planner.computeMatches(
            listOf(fresh, expired, expiringExactlyNow),
            listOf(home),
            listOf(rule("r")),
            mapOf(Phenomenon.SOLAR_ECLIPSE to AlwaysVisibleModel()),
            VisibilityContext(now = now, ovationGrid = null),
        )

        assertEquals(listOf("se:fresh"), matches.map { it.occ.id })
    }

    @Test
    fun anEphemerisOccurrenceWithNoExpiryNeverExpires() {
        // §5: "null for ephemeris events, which never go stale" -- an
        // eclipse decades out must not be filtered as expired.
        val home = loc("home", "Home")

        val matches = Planner.computeMatches(
            listOf(occ(expiresAt = null)),
            listOf(home),
            listOf(rule("r")),
            mapOf(Phenomenon.SOLAR_ECLIPSE to AlwaysVisibleModel()),
            VisibilityContext(now = now, ovationGrid = null),
        )

        assertEquals(1, matches.size)
    }

    @Test
    fun aBestViewingAnchorDedupsAcrossNearbyLocationsInsteadOfBuzzingTwice() {
        // §9.3's whole point: "Home" and "Office" 10 km apart produce one
        // notification. Each location solves its own dusk, so their
        // bestViewingStart values differ by a minute or two -- which used to
        // be enough to split the key and buzz twice per lead (ADR 0013).
        val home = loc("home", "Home")
        val office = loc("office", "Office")
        val shower = showerOcc()
        val r = rule("major-showers", leads = listOf(3.days, 6.hours), phenomenon = Phenomenon.METEOR_SHOWER, anchor = Anchor.BEST_VIEWING)
        val homeBest = Instant.parse("2026-01-31T21:14:00Z")
        val officeBest = homeBest + 78.seconds

        val matches = listOf(
            Match(r, shower, home, showerVisres(Quality.GOOD, homeBest)),
            Match(r, shower, office, showerVisres(Quality.EXCELLENT, officeBest)),
        )

        val desired = Planner.desiredNotifications(matches, now, utc)

        assertEquals(2, desired.size, "expected one notification per lead, not per location")
        // Anchored on the best-quality location -- the same one whose
        // viewing window the body quotes (§9.3), so the fire time and the
        // copy can't disagree.
        assertEquals(setOf(officeBest - 3.days, officeBest - 6.hours), desired.map { it.fireAt }.toSet())
        assertEquals(setOf(office.id), desired.map { it.locationId }.toSet())
        assertEquals(
            setOf("ms:2026:PER|${officeBest.epochSeconds}|${3.days.inWholeSeconds}", "ms:2026:PER|${officeBest.epochSeconds}|${6.hours.inWholeSeconds}"),
            desired.map { it.id }.toSet(),
        )
    }

    @Test
    fun aBestViewingRuleStillDedupsAgainstAPeakRuleWhenTheAnchorFallsBackToPeak() {
        // §9.1: BEST_VIEWING falls back to PEAK when there's no viewing
        // window. The fallback must keep landing on the *same* key a
        // PEAK-anchored rule produces -- one notification, listing the
        // first matching rule (§9.3).
        val home = loc("home", "Home")
        val shower = showerOcc()
        val peakRule = rule("peak-rule", phenomenon = Phenomenon.METEOR_SHOWER)
        val bestViewingRule = rule("best-viewing-rule", phenomenon = Phenomenon.METEOR_SHOWER, anchor = Anchor.BEST_VIEWING)
        val noWindow = showerVisres(Quality.GOOD, peak).copy(localDetails = null)

        val desired = Planner.desiredNotifications(
            listOf(Match(peakRule, shower, home, noWindow), Match(bestViewingRule, shower, home, noWindow)),
            now,
            utc,
        )

        assertEquals(1, desired.size)
        assertEquals(peakRule.id, desired.first().ruleId, "expected the first matching rule")
    }

    @Test
    fun aLeadComputedInThePastIsDropped() {
        // §7.4.3: "a lead whose computed fire_at is already in the past at plan
        // time must be dropped, not queued". The drop happens in reconcile --
        // the only place that can tell a never-planned past lead from one that
        // was scheduled and then missed (§10.4) -- so assert it there, through
        // the composed pipeline, not on desiredNotifications alone.
        val home = loc("home", "Home")
        // Peak already passed relative to `now`; every lead's fireAt is in the past.
        val pastOcc = occ(peakTime = now - 1.hours, windowEnd = now + 1.hours)
        val matches = listOf(Match(rule("r", leads = listOf(1.days, 2.hours)), pastOcc, home, visres(Quality.GOOD)))

        val desired = Planner.desiredNotifications(matches, now, utc)
        val reconciled = Planner.reconcile(previous = emptyList(), desired = desired, now = now, occurrencesById = mapOf(pastOcc.id to pastOcc))

        assertTrue(reconciled.isEmpty(), "expected all leads to be dropped as computed-in-the-past, even though the occurrence's window still contains `now`")
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
        // The whole point of issue #48: the overdue row and the desired set both
        // come out of the real pipeline here. The old version hand-built a
        // `desired` entry with a past fireAt that desiredNotifications could
        // never produce, so it passed while the composed pipeline cancelled the
        // row instead.
        val home = loc("home", "Home")
        val theOcc = occ(windowEnd = Instant.parse("2026-02-01T15:00:00Z"))
        val matches = listOf(Match(rule("r", leads = listOf(1.days)), theOcc, home, visres(Quality.GOOD)))
        val overdue = Planner.desiredNotifications(matches, now, utc).single() // fires 2026-01-31T12:00Z
        assertTrue(overdue.fireAt < Instant.parse("2026-02-01T13:00:00Z"))

        // Device off across the fire time; the eclipse is under way (peak 12:00, window to 15:00).
        val laterNow = Instant.parse("2026-02-01T13:00:00Z")
        val stillDesired = Planner.desiredNotifications(matches, laterNow, utc)
        val caughtUp = Planner.reconcile(listOf(overdue), stillDesired, laterNow, mapOf(theOcc.id to theOcc)).single()
        assertEquals(NotificationStatus.PENDING, caughtUp.status, "still schedulable, so the platform layer fires it at once")
        assertEquals(overdue.fireAt, caughtUp.fireAt, "the row keeps the time it was *due*; `fireAt <= now` is what makes it fire")

        // Same row after the window closed: no longer worth firing, but it was
        // genuinely scheduled, so it becomes history rather than vanishing.
        val pastWindowNow = Instant.parse("2026-02-01T16:00:00Z")
        val missed = Planner.reconcile(listOf(overdue), Planner.desiredNotifications(matches, pastWindowNow, utc), pastWindowNow, mapOf(theOcc.id to theOcc))
        assertEquals(NotificationStatus.MISSED, missed.single().status)
    }

    @Test
    fun anOverdueRowThatIsNoLongerDesiredIsMissedRatherThanFiredOrCancelled() {
        // The user disabled the rule (or the occurrence was withdrawn, §6.3)
        // while the device was off. §10.4's catch-up must not resurrect it --
        // but CANCELLED would claim the app cancelled a reminder whose moment
        // had already passed, so it lands in history as MISSED.
        val home = loc("home", "Home")
        val theOcc = occ(windowEnd = Instant.parse("2026-02-01T15:00:00Z"))
        val matches = listOf(Match(rule("r", leads = listOf(1.days)), theOcc, home, visres(Quality.GOOD)))
        val overdue = Planner.desiredNotifications(matches, now, utc).single()
        val laterNow = Instant.parse("2026-02-01T13:00:00Z") // still inside the occurrence's window

        val result = Planner.reconcile(listOf(overdue), emptyList(), laterNow, mapOf(theOcc.id to theOcc))

        assertEquals(NotificationStatus.MISSED, result.single().status)
    }

    @Test
    fun catchingUpAnOverdueRowIsIdempotentAcrossRepeatedReplans() {
        // §17.6: replan runs on every source upsert and app start, so the
        // catch-up branch is hit again and again until the platform layer
        // actually fires the row. It must be a fixed point, or the determinism
        // guard (and the natural-key design behind it) is worthless.
        val home = loc("home", "Home")
        val theOcc = occ(windowEnd = Instant.parse("2026-02-01T15:00:00Z"))
        val matches = listOf(Match(rule("r", leads = listOf(1.days)), theOcc, home, visres(Quality.GOOD)))
        val overdue = Planner.desiredNotifications(matches, now, utc)
        val laterNow = Instant.parse("2026-02-01T13:00:00Z")
        val desired = Planner.desiredNotifications(matches, laterNow, utc)

        val first = Planner.reconcile(overdue, desired, laterNow, mapOf(theOcc.id to theOcc))
        val second = Planner.reconcile(first, desired, laterNow, mapOf(theOcc.id to theOcc))

        assertEquals(first, second, "a caught-up row must not drift on the next replan")
    }

    @Test
    fun aMissedRowIsNotResurrectedByALaterReplanWhoseLeadIsAlreadyPast() {
        // MISSED/CANCELLED rows are re-desired as fresh when the same key comes
        // back -- but only into the future. Re-desiring one onto a fire time
        // that has already passed would hand it straight back to the catch-up
        // branch and fire a reminder the app already gave up on.
        val home = loc("home", "Home")
        val theOcc = occ(windowEnd = Instant.parse("2026-02-01T15:00:00Z"))
        val matches = listOf(Match(rule("r", leads = listOf(1.days)), theOcc, home, visres(Quality.GOOD)))
        val laterNow = Instant.parse("2026-02-01T13:00:00Z")
        val alreadyMissed = Planner.desiredNotifications(matches, now, utc).map { it.copy(status = NotificationStatus.MISSED) }

        val result = Planner.reconcile(alreadyMissed, Planner.desiredNotifications(matches, laterNow, utc), laterNow, mapOf(theOcc.id to theOcc))

        assertEquals(NotificationStatus.MISSED, result.single().status)
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
    fun aPendingRowWhoseRecomputedFireTimeLandsInThePastIsCancelledNotFired() {
        // §6.3 refines the anchor (or a timezone change reshuffles a quiet-hours
        // deferral) and the same key now resolves to a time that has already
        // passed. §7.4.3: that lead was in the past the moment it was computed,
        // so it is dropped -- pushing it into the still-PENDING row would hand it
        // straight to §10.4's catch-up branch on the next pass and fire it.
        val home = loc("home", "Home")
        val theOcc = occ(windowEnd = peak + 3.hours)
        val matches = listOf(Match(rule("r", leads = listOf(1.days)), theOcc, home, visres(Quality.GOOD)))
        val previous = Planner.desiredNotifications(matches, now, utc) // fires 2026-01-31T12:00Z
        val movedIntoThePast = previous.map { it.copy(fireAt = now - 1.hours) }

        val result = Planner.reconcile(previous, movedIntoThePast, now, mapOf(theOcc.id to theOcc))

        assertEquals(NotificationStatus.CANCELLED, result.single().status)
        // ...and it stays cancelled: a second pass must not re-desire it either.
        assertEquals(result, Planner.reconcile(result, movedIntoThePast, now, mapOf(theOcc.id to theOcc)))
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

    // ---- first-seen cooldown (issue #57, ADR 0017) ----

    private fun firstSeenCandidate(
        occId: String,
        ruleId: String = "r",
        locationId: String = "home",
        fireAt: Instant = now,
        status: NotificationStatus = NotificationStatus.PENDING,
        firedAt: Instant? = null,
    ) = PlannedNotification(
        id = "$occId|1|first", occurrenceId = occId, ruleId = ruleId, locationId = locationId,
        fireAt = fireAt, status = status, precision = Precision.EXACT,
        title = "t", body = "b", createdAt = fireAt, firedAt = firedAt,
    )

    @Test
    fun firstSeenCooldownSuppressesARepeatWithinTheWindowForTheSameRuleAndLocation() {
        val r = rule("r", notifyOnFirstSeen = true, firstSeenCooldown = 2.hours)
        // The earlier alert was actually delivered to the user.
        val previous = listOf(firstSeenCandidate("occ:1", status = NotificationStatus.FIRED, firedAt = now - 30.minutes))
        // A churning-identity occurrence (aurora NOWCAST): a *different* occurrenceId every poll.
        val desired = listOf(firstSeenCandidate("occ:2", fireAt = now))

        val result = Planner.applyFirstSeenCooldown(desired, previous, mapOf(r.id to r), now)

        assertTrue(result.isEmpty(), "a second first-seen candidate inside the cooldown window must be suppressed")
    }

    @Test
    fun firstSeenCooldownAllowsANewAlertOnceTheWindowElapses() {
        val r = rule("r", notifyOnFirstSeen = true, firstSeenCooldown = 2.hours)
        val previous = listOf(firstSeenCandidate("occ:1", status = NotificationStatus.FIRED, firedAt = now - 3.hours))
        val desired = listOf(firstSeenCandidate("occ:2", fireAt = now))

        val result = Planner.applyFirstSeenCooldown(desired, previous, mapOf(r.id to r), now)

        assertEquals(1, result.size, "once the cooldown has elapsed a fresh first-seen notification must go through")
    }

    @Test
    fun firstSeenCooldownDoesNotApplyWithoutARuleSetting() {
        val r = rule("r", notifyOnFirstSeen = true, firstSeenCooldown = null)
        val previous = listOf(firstSeenCandidate("occ:1", status = NotificationStatus.FIRED, firedAt = now - 1.minutes))
        val desired = listOf(firstSeenCandidate("occ:2", fireAt = now))

        val result = Planner.applyFirstSeenCooldown(desired, previous, mapOf(r.id to r), now)

        assertEquals(1, result.size, "no cooldown configured means the historical (uncooled) behaviour")
    }

    @Test
    fun firstSeenCooldownIsScopedPerLocationAndRule() {
        val r = rule("nowcast", notifyOnFirstSeen = true, firstSeenCooldown = 2.hours)
        val previous = listOf(
            firstSeenCandidate("occ:1", ruleId = "other-rule", locationId = "home", status = NotificationStatus.FIRED, firedAt = now - 30.minutes),
            firstSeenCandidate("occ:1", ruleId = "nowcast", locationId = "cabin", status = NotificationStatus.FIRED, firedAt = now - 30.minutes),
        )
        val desired = listOf(firstSeenCandidate("occ:2", ruleId = "nowcast", locationId = "home", fireAt = now))

        val result = Planner.applyFirstSeenCooldown(desired, previous, mapOf(r.id to r), now)

        assertEquals(1, result.size, "a different rule or a different location must not consume the same cooldown")
    }

    @Test
    fun firstSeenCooldownIgnoresANonFiredPreviousNotification() {
        // CodeRabbit review, PR #114: a still-undelivered PENDING/REGISTERED
        // row (or one that ended up CANCELLED/MISSED without ever reaching
        // the user) must never block a replacement. Otherwise, once the
        // occurrence backing that undelivered row churns again (aurora
        // NOWCAST mints a fresh id every fetch), `reconcile` marks the stale
        // row MISSED because its withdrawn occurrence disappears from
        // `occurrencesById` -- and with the replacement suppressed too, the
        // user ends up with no alert for an aurora that was genuinely
        // ongoing.
        val r = rule("r", notifyOnFirstSeen = true, firstSeenCooldown = 2.hours)
        val previous = listOf(
            firstSeenCandidate("occ:1", status = NotificationStatus.PENDING, fireAt = now - 30.minutes),
            firstSeenCandidate("occ:1b", status = NotificationStatus.REGISTERED, fireAt = now - 30.minutes),
            firstSeenCandidate("occ:1c", status = NotificationStatus.CANCELLED, fireAt = now - 30.minutes),
            firstSeenCandidate("occ:1d", status = NotificationStatus.MISSED, fireAt = now - 30.minutes),
        )
        val desired = listOf(firstSeenCandidate("occ:2", fireAt = now))

        val result = Planner.applyFirstSeenCooldown(desired, previous, mapOf(r.id to r), now)

        assertEquals(1, result.size, "a notification that was never actually delivered must not block a fresh alert")
    }

    @Test
    fun firstSeenCooldownNeverSuppressesLeadBasedCandidates() {
        val r = rule("scheduled", leads = listOf(1.days), firstSeenCooldown = 2.hours)
        val theOcc = occ()
        val leadCandidate = Planner.desiredNotifications(listOf(Match(r, theOcc, loc("home", "Home"), visres(Quality.GOOD))), now, utc)

        val result = Planner.applyFirstSeenCooldown(leadCandidate, leadCandidate, mapOf(r.id to r), now)

        assertEquals(leadCandidate, result, "cooldown only targets notifyOnFirstSeen rows, not scheduled leads")
    }
}
