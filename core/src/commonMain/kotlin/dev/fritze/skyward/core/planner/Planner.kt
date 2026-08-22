package dev.fritze.skyward.core.planner

import dev.fritze.skyward.core.format.renderNotificationCopy
import dev.fritze.skyward.core.model.Certainty
import dev.fritze.skyward.core.model.LocalDetails
import dev.fritze.skyward.core.model.NotificationStatus
import dev.fritze.skyward.core.model.Occurrence
import dev.fritze.skyward.core.model.Phenomenon
import dev.fritze.skyward.core.model.PlannedNotification
import dev.fritze.skyward.core.model.Precision
import dev.fritze.skyward.core.model.Quality
import dev.fritze.skyward.core.model.SavedLocation
import dev.fritze.skyward.core.model.VisibilityResult
import dev.fritze.skyward.core.model.hasExpiredAt
import dev.fritze.skyward.core.rules.Anchor
import dev.fritze.skyward.core.rules.QuietHours
import dev.fritze.skyward.core.rules.Rule
import dev.fritze.skyward.core.rules.RuleEngine
import dev.fritze.skyward.core.rules.isInLocalHourRange
import dev.fritze.skyward.core.visibility.VisibilityContext
import dev.fritze.skyward.core.visibility.VisibilityModel
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration
import kotlin.time.Instant

/** §9.2 step 3: a rule matching an (occurrence, location). */
data class Match(val rule: Rule, val occ: Occurrence, val loc: SavedLocation, val visres: VisibilityResult)

/**
 * §9-§10.4: turns occurrences + rules into the desired set of reminders,
 * then reconciles that against what was previously planned. Pure/stateless
 * — visibility caching and actual OS scheduling are the caller's job
 * (persistence, §11, and `AlarmScheduler`, §10.2, are both M3+).
 */
object Planner {

    /**
     * §9.2: run every enabled rule against every (occurrence, location) it
     * applies to — skipping occurrences whose forecast has expired (§5), so
     * a source that has been unreachable for hours can't keep matching
     * rules with data it no longer stands behind.
     */
    fun computeMatches(
        occurrences: List<Occurrence>,
        locations: List<SavedLocation>,
        rules: List<Rule>,
        visibilityModels: Map<Phenomenon, VisibilityModel>,
        ctx: VisibilityContext,
    ): List<Match> {
        val matches = mutableListOf<Match>()
        val visResCache = HashMap<Pair<String, String>, VisibilityResult>()
        for (occ in occurrences) {
            if (occ.hasExpiredAt(ctx.now)) continue // §5: stale forecast data matches nothing
            val model = visibilityModels[occ.phenomenon] ?: continue
            val candidateRules = rules.filter { it.enabled && occ.phenomenon in it.phenomena }
            if (candidateRules.isEmpty()) continue
            for (loc in locations) {
                val visres = visResCache.getOrPut(occ.id to loc.id) { model.evaluate(occ, loc, ctx) }
                for (rule in candidateRules) {
                    if (RuleEngine.matches(rule, occ, loc, visres, ctx.now)) {
                        matches += Match(rule, occ, loc, visres)
                    }
                }
            }
        }
        return matches
    }

    /**
     * §9.3/§10.4: expand each match's schedule into candidate fire times,
     * dedup by `(occurrenceId, anchorTime, lead-or-"first")`, and render the
     * winning (rule, location) pair into a [PlannedNotification] via §10.5's
     * `core/format` templates.
     *
     * Candidates whose `fireAt` is already in the past are **kept** here and
     * filtered in [reconcile], not dropped on the spot. §7.4.3 and §10.4 draw
     * their line between "computed in the past" (drop) and "genuinely
     * scheduled, then missed" (fire immediately if still within the
     * occurrence's window) — and only [reconcile] knows which of the two a
     * given key is, because only it has seen what was planned before.
     * Dropping past leads here made §10.4's catch-up unreachable in the
     * composed pipeline: the row fell out of the desired set and reconcile
     * cancelled it (issue #48).
     *
     * The BEST_VIEWING anchor is resolved once per occurrence rather than
     * per match, so nearby locations can't split the dedup key — see ADR
     * 0010 and [bestViewingAnchorsByOccurrence].
     */
    fun desiredNotifications(matches: List<Match>, now: Instant, deviceZone: TimeZone): List<PlannedNotification> {
        data class Candidate(
            val key: String,
            val rule: Rule,
            val occ: Occurrence,
            val loc: SavedLocation,
            val visres: VisibilityResult,
            val fireAt: Instant,
            val leadUntilAnchor: Duration?,
        )

        val bestViewingAnchors = bestViewingAnchorsByOccurrence(matches)

        val candidates = mutableListOf<Candidate>()
        for (m in matches) {
            val anchorTime = when (m.rule.schedule.anchor) {
                // ADR 0013: one anchor per occurrence, not per location.
                Anchor.BEST_VIEWING -> bestViewingAnchors[m.occ.id]
                else -> resolveAnchor(m.rule.schedule.anchor, m.occ, m.visres)
            }
            if (anchorTime != null) {
                for (lead in m.rule.schedule.leads) {
                    val rawFireAt = anchorTime - lead
                    val fireAt = applyQuietHours(rawFireAt, m.rule.schedule.quietHours, m.occ, deviceZone) ?: continue
                    val key = "${m.occ.id}|${anchorTime.epochSeconds}|${lead.inWholeSeconds}"
                    candidates += Candidate(key, m.rule, m.occ, m.loc, m.visres, fireAt, leadUntilAnchor = lead)
                }
            }
            if (m.rule.schedule.notifyOnFirstSeen) {
                // §10.4: first-seen rows *key* on peakTime, or window.start
                // when peakTime is null — deliberately not `now` — so the
                // key stays stable and only ever produces one first-seen
                // notification per occurrence, ever. The *fire time* is
                // `now` itself: "fire as soon as occurrence first matches"
                // (§9.6) means discovery time, not the (possibly distant)
                // anchor.
                val firstSeenAnchor = m.occ.peakTime ?: m.occ.window.start
                val fireAt = applyQuietHours(now, m.rule.schedule.quietHours, m.occ, deviceZone) ?: continue
                val key = "${m.occ.id}|${firstSeenAnchor.epochSeconds}$FIRST_SEEN_KEY_SUFFIX"
                candidates += Candidate(key, m.rule, m.occ, m.loc, m.visres, fireAt, leadUntilAnchor = null)
            }
        }

        return candidates.groupBy { it.key }.map { (key, group) ->
            val winner = group.first() // "one notification, listing the first matching rule" (§9.3)
            val bestLocation = group.maxBy { it.visres.quality } // "body shows the best (highest-quality) location"
            val copy = renderNotificationCopy(winner.occ, bestLocation.loc, bestLocation.visres, winner.rule, winner.fireAt, winner.leadUntilAnchor)
            PlannedNotification(
                id = key,
                occurrenceId = winner.occ.id,
                ruleId = winner.rule.id,
                locationId = bestLocation.loc.id,
                fireAt = winner.fireAt,
                status = NotificationStatus.PENDING,
                precision = Precision.EXACT,
                title = copy.title,
                body = copy.body,
                createdAt = now,
                firedAt = null,
            )
        }
    }

    /**
     * §10.4 extension (issue #57, ADR 0017): drops a `notifyOnFirstSeen`
     * candidate whose rule set [dev.fritze.skyward.core.rules.NotifySchedule.firstSeenCooldown]
     * when a previous notification for the same `(ruleId, locationId)` pair
     * was actually *delivered* (`FIRED`, with a `firedAt`) within that
     * window.
     *
     * Exists because first-seen dedup keys on the *occurrence* (§10.4), and
     * a source like aurora NOWCAST deliberately mints a new occurrence id
     * every fetch (§7.3.3), so dedup alone gives no protection there: every
     * poll while the event persists would otherwise produce its own
     * first-seen candidate. This runs on [desiredNotifications]'s output
     * rather than folding into it, because the check needs `previous`
     * (already-planned rows), which [desiredNotifications] deliberately
     * doesn't take -- see its own doc comment.
     *
     * Deliberately restricted to `FIRED` rows, not merely "not cancelled/
     * missed": a still-`PENDING`/`REGISTERED` row hasn't reached the user
     * yet. If it were allowed to suppress a replacement and the occurrence
     * then churns again before the platform layer delivers it, [reconcile]
     * marks the stale row `MISSED` once its withdrawn occurrence disappears
     * from `occurrencesById` -- and with the replacement suppressed too, the
     * user would receive nothing for an aurora that was genuinely ongoing.
     * Gating on `FIRED` means the cooldown can only ever suppress a
     * *repeat* of an alert the user already got, never risk swallowing the
     * only one.
     *
     * Deliberately keyed on `(ruleId, locationId)` alone, not on any
     * phenomenon-specific "is this materially stronger" signal (e.g. Kp):
     * the withdrawn occurrence a comparison would need is already gone from
     * the DB by the next poll (§6.3's "drop withdrawn FORECAST rows"), and
     * threading phenomenon data onto [PlannedNotification] would leak it out
     * of the sealed `OccurrencePayload` it deliberately lives in (§5).
     */
    internal fun applyFirstSeenCooldown(
        desired: List<PlannedNotification>,
        previous: List<PlannedNotification>,
        rulesById: Map<String, Rule>,
        now: Instant,
    ): List<PlannedNotification> = desired.filterNot { candidate ->
        if (!candidate.id.endsWith(FIRST_SEEN_KEY_SUFFIX)) return@filterNot false
        val cooldown = rulesById[candidate.ruleId]?.schedule?.firstSeenCooldown ?: return@filterNot false
        previous.any { p ->
            p.status == NotificationStatus.FIRED &&
                p.ruleId == candidate.ruleId &&
                p.locationId == candidate.locationId &&
                p.id != candidate.id &&
                p.firedAt != null &&
                p.firedAt > now - cooldown
        }
    }

    private const val FIRST_SEEN_KEY_SUFFIX = "|first"

    /**
     * §10.4 reconciliation: insert new desired rows as PENDING; a row no
     * longer desired is CANCELLED unless it's already terminal (FIRED/
     * MISSED/CANCELLED, kept as history); a row whose `fireAt` passed while
     * still PENDING/REGISTERED (device off) fires immediately if it is still
     * desired and the occurrence's window still contains `now`, else MISSED.
     *
     * Every row in [previous] comes back exactly once: callers persist the
     * result by upserting it, so a row silently dropped here would linger in
     * the database in whatever state it was already in.
     */
    fun reconcile(
        previous: List<PlannedNotification>,
        desired: List<PlannedNotification>,
        now: Instant,
        occurrencesById: Map<String, Occurrence>,
    ): List<PlannedNotification> {
        val desiredById = desired.associateBy { it.id }
        val result = mutableListOf<PlannedNotification>()

        for (p in previous) {
            val d = desiredById[p.id]
            result += when {
                p.status == NotificationStatus.FIRED -> p // permanent history
                // Re-desired after a cancellation/miss: treat as fresh — but
                // only while the fresh row is still in the future. Resurrecting
                // a MISSED row onto a fire time that has already passed would
                // fire it on the next pass, which is exactly what §7.4.3 forbids.
                p.status.isTerminalButNotFired() -> if (d != null && now <= d.fireAt) d else p
                p.fireAt < now -> catchUpOrMiss(p, stillDesired = d != null, now, occurrencesById)
                d == null -> p.copy(status = NotificationStatus.CANCELLED)
                // Same dedup key, still pending, but the desired fire time moved
                // (e.g. device timezone change shifts a quiet-hours deferral, or
                // §6.3 refined the anchor) -- the key doesn't encode `fireAt`, so
                // without this the stale time would stick forever. A drift *into
                // the past* is not propagated: that recomputed lead was in the
                // past the moment it was computed, and §7.4.3 says such a lead is
                // dropped rather than queued -- pushing it into the row would
                // hand it to the catch-up branch above on the next pass and fire
                // it. Dropping an already-queued row means cancelling it.
                d.fireAt != p.fireAt -> if (now <= d.fireAt) p.copy(fireAt = d.fireAt) else p.copy(status = NotificationStatus.CANCELLED)
                else -> p // unchanged
            }
        }

        val previousIds = previous.mapTo(mutableSetOf()) { it.id }
        for (d in desired) {
            // §7.4.3: "a lead whose computed fire_at is already in the past at
            // plan time must be dropped, not queued" — never planned, so §10.4's
            // catch-up does not apply to it. `notifyOnFirstSeen` rows fire at
            // `now` and so are never caught by this.
            if (d.id in previousIds || d.fireAt < now) continue
            result += d
        }
        return result
    }

    /**
     * §10.4: "A notification whose `fire_at` passed while unregistered (device
     * off) fires immediately on next planner run if still within
     * `occurrence.window`, else marked MISSED."
     *
     * "Fires immediately" is expressed by leaving the row PENDING/REGISTERED
     * with its original past `fireAt` rather than rewriting it to `now`: every
     * consumer already treats a schedulable row with `fireAt <= now` as due
     * (`DesktopScheduler.run`, and `AlarmManager`/`WorkManager` on Android,
     * which run a trigger time in the past at once). Keeping the original time
     * also keeps the row honest about when the reminder was *due*, which is
     * what history and §10.3's "While you were away" panel show — and it keeps
     * the pass idempotent, so §17.6's determinism guard still sees a zero-diff
     * second run.
     *
     * [stillDesired] is false when this pass no longer wants the row at all
     * (the rule was disabled or edited, the occurrence was withdrawn, the
     * forecast no longer matches). Such a row must not fire — but it was
     * genuinely scheduled and its moment has passed, so MISSED is the honest
     * history entry, not CANCELLED.
     */
    private fun catchUpOrMiss(
        p: PlannedNotification,
        stillDesired: Boolean,
        now: Instant,
        occurrencesById: Map<String, Occurrence>,
    ): PlannedNotification {
        val occ = occurrencesById[p.occurrenceId]
        val fireNow = stillDesired && occ != null && now <= occ.window.end
        return if (fireNow) p else p.copy(status = NotificationStatus.MISSED)
    }

    private fun NotificationStatus.isTerminalButNotFired() = this == NotificationStatus.CANCELLED || this == NotificationStatus.MISSED

    /**
     * ADR 0013. §9.3's dedup key is `(occurrenceId, anchorTime, lead)`
     * expressly so "Home" and "Office" 10 km apart produce one
     * notification. A BEST_VIEWING anchor breaks that on its own, because
     * §9.1 resolves it from *each location's* `bestViewingStart`, and two
     * locations 10 km apart solve dusk a minute or two apart — different
     * anchors, different keys, two buzzes for the one shower peak.
     *
     * So resolve that anchor once per occurrence, from the same
     * highest-quality location whose `localDetails` the notification body
     * will quote (§9.3's "body shows the best location"): every match on
     * the occurrence then shares one anchor, the key dedups as §9.3
     * intends, and the fire time agrees with the viewing window the copy
     * names. Occurrences with no BEST_VIEWING match are absent from the
     * result — the anchor is only consulted for rules that ask for it.
     */
    private fun bestViewingAnchorsByOccurrence(matches: List<Match>): Map<String, Instant?> =
        matches
            .filter { it.rule.schedule.anchor == Anchor.BEST_VIEWING }
            .groupBy { it.occ.id }
            .mapValues { (_, group) ->
                val best = group.maxBy { it.visres.quality }
                resolveAnchor(Anchor.BEST_VIEWING, best.occ, best.visres)
            }

    private fun resolveAnchor(anchor: Anchor, occ: Occurrence, visres: VisibilityResult): Instant? = when (anchor) {
        Anchor.PEAK -> occ.peakTime
        Anchor.WINDOW_START -> occ.window.start
        Anchor.BEST_VIEWING -> when (val details = visres.localDetails) {
            is LocalDetails.MeteorLocal -> details.bestViewingStart ?: occ.peakTime
            is LocalDetails.CometLocal -> details.bestViewingStart ?: occ.peakTime
            else -> occ.peakTime
        }
    }

    /**
     * §9.1: quiet hours are device-local (unlike `PeakOnWeekend`/
     * `PeakInLocalHours`, which use the *location's* approximate solar
     * time, ADR 0005). Defers into the quiet window's end; drops (returns
     * `null`) for a FORECAST occurrence whose window would end before the
     * deferred time, rather than deferring past the point the reminder is
     * still useful.
     */
    private fun applyQuietHours(fireAt: Instant, quietHours: QuietHours?, occ: Occurrence, zone: TimeZone): Instant? {
        if (quietHours == null) return fireAt
        val local = fireAt.toLocalDateTime(zone)
        if (!isInLocalHourRange(local, quietHours.fromHour, quietHours.toHour)) return fireAt

        // Build the deferred *wall-clock* time and convert that to an
        // Instant directly (rather than adding elapsed minutes to
        // start-of-day), so a DST transition between `fireAt` and the
        // deferred time doesn't shift the result by an hour.
        val wrapped = quietHours.fromHour > quietHours.toHour
        val eveningHalf = wrapped && local.hour >= quietHours.fromHour
        val deferredDate = if (eveningHalf) local.date.plus(1, DateTimeUnit.DAY) else local.date
        val deferred = LocalDateTime(deferredDate, LocalTime(quietHours.toHour, 0)).toInstant(zone)

        return if (occ.certainty == Certainty.FORECAST && deferred > occ.window.end) null else deferred
    }
}
