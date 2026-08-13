package dev.fritze.skyward.core.planner

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
import dev.fritze.skyward.core.rules.Anchor
import dev.fritze.skyward.core.rules.QuietHours
import dev.fritze.skyward.core.rules.Rule
import dev.fritze.skyward.core.rules.RuleEngine
import dev.fritze.skyward.core.rules.isInLocalHourRange
import dev.fritze.skyward.core.visibility.VisibilityContext
import dev.fritze.skyward.core.visibility.VisibilityModel
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration.Companion.minutes
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

    /** §9.2: run every enabled rule against every (occurrence, location) it applies to. */
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
     * winning (rule, location) pair into a [PlannedNotification]. A lead
     * computed in the past is dropped, not fired (§7.4.3). Notification
     * copy here is a placeholder for the full §10.5 templates (`core/format/`,
     * M3) — enough to be useful for debugging/testing, not the polished text.
     */
    fun desiredNotifications(matches: List<Match>, now: Instant, deviceZone: TimeZone): List<PlannedNotification> {
        data class Candidate(val key: String, val rule: Rule, val occ: Occurrence, val loc: SavedLocation, val visres: VisibilityResult, val fireAt: Instant)

        val candidates = mutableListOf<Candidate>()
        for (m in matches) {
            val anchorTime = resolveAnchor(m.rule.schedule.anchor, m.occ, m.visres)
            if (anchorTime != null) {
                for (lead in m.rule.schedule.leads) {
                    val rawFireAt = anchorTime - lead
                    val fireAt = applyQuietHours(rawFireAt, m.rule.schedule.quietHours, m.occ, deviceZone) ?: continue
                    if (fireAt < now) continue // §7.4.3: a lead computed in the past is dropped, not fired
                    val key = "${m.occ.id}|${anchorTime.epochSeconds}|${lead.inWholeSeconds}"
                    candidates += Candidate(key, m.rule, m.occ, m.loc, m.visres, fireAt)
                }
            }
            if (m.rule.schedule.notifyOnFirstSeen) {
                // §10.4: first-seen rows anchor on peakTime, or window.start
                // when peakTime is null — deliberately not `schedule.anchor`,
                // so the key stays stable regardless of which anchor mode
                // the rule otherwise uses for its leads.
                val firstSeenAnchor = m.occ.peakTime ?: m.occ.window.start
                val fireAt = applyQuietHours(firstSeenAnchor, m.rule.schedule.quietHours, m.occ, deviceZone) ?: continue
                val key = "${m.occ.id}|${firstSeenAnchor.epochSeconds}|first"
                candidates += Candidate(key, m.rule, m.occ, m.loc, m.visres, fireAt)
            }
        }

        return candidates.groupBy { it.key }.map { (key, group) ->
            val winner = group.first() // "one notification, listing the first matching rule" (§9.3)
            val bestLocation = group.maxBy { it.visres.quality } // "body shows the best (highest-quality) location"
            PlannedNotification(
                id = key,
                occurrenceId = winner.occ.id,
                ruleId = winner.rule.id,
                locationId = bestLocation.loc.id,
                fireAt = winner.fireAt,
                status = NotificationStatus.PENDING,
                precision = Precision.EXACT,
                title = winner.occ.title,
                body = "${bestLocation.loc.name}: ${bestLocation.visres.quality}",
                createdAt = now,
                firedAt = null,
            )
        }
    }

    /**
     * §10.4 reconciliation: insert new desired rows as PENDING; a row no
     * longer desired is CANCELLED unless it's already terminal (FIRED/
     * MISSED/CANCELLED, kept as history); a row whose `fireAt` passed while
     * still PENDING/REGISTERED (device off) fires immediately if the
     * occurrence's window still contains `now`, else MISSED.
     */
    fun reconcile(
        previous: List<PlannedNotification>,
        desired: List<PlannedNotification>,
        now: Instant,
        occurrencesById: Map<String, Occurrence>,
    ): List<PlannedNotification> {
        val previousById = previous.associateBy { it.id }
        val desiredIds = desired.map { it.id }.toSet()
        val result = mutableListOf<PlannedNotification>()

        for (d in desired) {
            val prev = previousById[d.id]
            result += when {
                prev == null -> d
                prev.status == NotificationStatus.FIRED -> prev // permanent history
                prev.status.isTerminalButNotFired() -> d // re-desired after cancellation/miss: treat as fresh
                prev.fireAt < now -> {
                    val occ = occurrencesById[d.occurrenceId]
                    if (occ != null && now <= occ.window.end) {
                        prev.copy(fireAt = now) // device-off catch-up: fire immediately
                    } else {
                        prev.copy(status = NotificationStatus.MISSED)
                    }
                }
                else -> prev // unchanged: still pending, still in the future
            }
        }

        for (p in previous) {
            if (p.id !in desiredIds && (p.status == NotificationStatus.PENDING || p.status == NotificationStatus.REGISTERED)) {
                result += p.copy(status = NotificationStatus.CANCELLED)
            } else if (p.id !in desiredIds) {
                result += p // FIRED/MISSED/CANCELLED already terminal
            }
        }
        return result
    }

    private fun NotificationStatus.isTerminalButNotFired() = this == NotificationStatus.CANCELLED || this == NotificationStatus.MISSED

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

        val startOfDay = LocalDateTime(local.date, LocalTime(0, 0)).toInstant(zone)
        val minutesIntoDay = (fireAt - startOfDay).inWholeMinutes
        val fromMinutes = quietHours.fromHour * 60L
        val toMinutes = quietHours.toHour * 60L
        val deferredMinutes = when {
            quietHours.fromHour <= quietHours.toHour -> toMinutes
            minutesIntoDay >= fromMinutes -> toMinutes + 24 * 60 // wrapped window, evening half -> next day
            else -> toMinutes // wrapped window, early-morning half -> same day
        }
        val deferred = startOfDay + deferredMinutes.minutes

        return if (occ.certainty == Certainty.FORECAST && deferred > occ.window.end) null else deferred
    }
}
