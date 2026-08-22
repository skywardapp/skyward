package dev.fritze.skyward.alarm

import dev.fritze.skyward.core.model.Certainty
import dev.fritze.skyward.core.model.MeteorShowerPayload
import dev.fritze.skyward.core.model.NotificationStatus
import dev.fritze.skyward.core.model.Occurrence
import dev.fritze.skyward.core.model.Phenomenon
import dev.fritze.skyward.core.model.PlannedNotification
import dev.fritze.skyward.core.model.Precision
import dev.fritze.skyward.core.model.TimeWindow
import java.util.UUID
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/**
 * Rows for the §17.5 suites to schedule, fire and re-register.
 *
 * Every id is fresh per call. `connectedAndroidTest` runs every method of
 * every class in one process against one on-disk database with no orchestrator
 * clearing app data in between, so a fixed id would let one test's leftovers
 * decide another's outcome -- and a `notify:<id>` work item that escaped a
 * teardown would land on a row a later test believes it owns.
 */
internal fun freshNotification(
    fireAt: Instant,
    status: NotificationStatus = NotificationStatus.PENDING,
    precision: Precision = Precision.EXACT,
    occurrenceId: String = "occ-${UUID.randomUUID()}",
) = PlannedNotification(
    id = "test-${UUID.randomUUID()}",
    occurrenceId = occurrenceId,
    ruleId = "rule-${UUID.randomUUID()}",
    locationId = "loc-${UUID.randomUUID()}",
    fireAt = fireAt,
    status = status,
    precision = precision,
    title = "Perseids peak tonight",
    // Deliberately says "at 02:30", not "around 02:30": §10.5's hedge is
    // applied at fire time by rewriting the clock time to "around 02:30", so a
    // body that already contained the word would let a missing hedge pass an
    // assertion looking for it.
    body = "Peaks at 02:30 local time, best after midnight.",
    createdAt = Clock.System.now(),
    firedAt = null,
)

/** A shower whose observing window is still open, so §10.4's catch-up applies. */
internal fun openWindowOccurrence(id: String, now: Instant) = meteorOccurrence(id, TimeWindow(now - 2.hours, now + 2.hours))

/** ...and one whose window closed an hour ago, so it is genuinely missed. */
internal fun closedWindowOccurrence(id: String, now: Instant) = meteorOccurrence(id, TimeWindow(now - 4.hours, now - 1.hours))

internal fun meteorOccurrence(id: String, window: TimeWindow) = Occurrence(
    id = id,
    phenomenon = Phenomenon.METEOR_SHOWER,
    sourceId = "showers",
    title = "Perseids",
    window = window,
    peakTime = window.start,
    certainty = Certainty.CERTAIN,
    payload = MeteorShowerPayload(
        iauCode = "PER", name = "Perseids", zhr = 100, zhrNote = null,
        radiantRaDeg = 46.2, radiantDecDeg = 57.4, speedKmS = 59.0, parentBody = "109P/Swift-Tuttle",
        activityStart = window.start, activityEnd = window.end, moonIlluminationAtPeak = 0.1,
    ),
    fetchedAt = window.start,
    expiresAt = null,
)
