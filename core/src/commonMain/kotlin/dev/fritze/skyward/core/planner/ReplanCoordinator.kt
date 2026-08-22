package dev.fritze.skyward.core.planner

import dev.fritze.skyward.core.model.Phenomenon
import dev.fritze.skyward.core.model.PlannedNotification
import dev.fritze.skyward.core.persistence.LocationRepo
import dev.fritze.skyward.core.persistence.NotificationRepo
import dev.fritze.skyward.core.persistence.OccurrenceRepo
import dev.fritze.skyward.core.persistence.RuleRepo
import dev.fritze.skyward.core.persistence.VisibilityCacheRepo
import dev.fritze.skyward.core.rules.Rule
import dev.fritze.skyward.core.visibility.OvationGrid
import dev.fritze.skyward.core.visibility.VisibilityContext
import dev.fritze.skyward.core.visibility.VisibilityModel
import dev.fritze.skyward.core.visibility.VisibilityResultCache
import kotlinx.datetime.TimeZone
import kotlin.time.Instant

/**
 * §9.7: the `SourceRunner.runDue` -> `Planner.replan()` step the design doc
 * refers to — reads current occurrences/locations/rules, runs the pure
 * §9/§10.4 pipeline, applies §13.3's mute suppression and `Planner`'s
 * first-seen cooldown (issue #57, ADR 0017), reconciles against what's
 * already in the DB, and persists the result. Deliberately stops at
 * persistence: turning the reconciled rows into actual OS alarms is
 * platform glue (`AlarmScheduler`, §10.2), not `:core`'s job.
 *
 * Also prunes old `FIRED` history (§10.4) on every run, rather than on a
 * separate daily job: `replan` already runs on every trigger the doc
 * names (source upsert, rule/location/settings edit, sync import,
 * boot/app start) and a `DELETE ... WHERE status = 'FIRED'` is cheap
 * enough to repeat, so there is no reason to wait for a dedicated daily
 * tick that doesn't otherwise exist on desktop. The sync export path
 * additionally prunes for itself right before reading history (see
 * `SyncViewModel`/`SyncSection`), so an export is never stale even if no
 * replan has run yet this session.
 */
class ReplanCoordinator(
    private val occurrenceRepo: OccurrenceRepo,
    private val locationRepo: LocationRepo,
    private val ruleRepo: RuleRepo,
    private val notificationRepo: NotificationRepo,
    private val visibilityCacheRepo: VisibilityCacheRepo,
    private val visibilityModels: Map<Phenomenon, VisibilityModel>,
    private val ovationGridProvider: suspend () -> OvationGrid? = { null },
) {

    /** Recomputes and persists the full desired/reconciled notification set. Returns it for callers that sync OS alarms. */
    suspend fun replan(now: Instant, deviceZone: TimeZone = TimeZone.currentSystemDefault()): List<PlannedNotification> {
        val occurrences = occurrenceRepo.getAll()
        val locations = locationRepo.getAll()
        val rules = ruleRepo.getEnabled() // includes hidden rules -- mutes/one-off reminders must still evaluate (§13.3)

        val ctx = VisibilityContext(now = now, ovationGrid = ovationGridProvider())
        // §9.2 step 1/§11: read-through visibility_cache in front of the real
        // models -- Planner itself stays pure (§4.2); the I/O lives here.
        val cache = VisibilityResultCache(visibilityCacheRepo.getAll(), deviceZone)
        val matches = Planner.computeMatches(occurrences, locations, rules, cache.wrap(visibilityModels), ctx)
        visibilityCacheRepo.upsertAll(cache.dirty)

        val suppressedOccurrenceIds = matches
            .filter { it.rule.hidden && isMuteSuppressor(it.rule) }
            .mapTo(mutableSetOf()) { it.occ.id }

        val previous = notificationRepo.getAll()
        val desired = Planner.desiredNotifications(matches, now, deviceZone)
            .filterNot { it.occurrenceId in suppressedOccurrenceIds }
            .let { Planner.applyFirstSeenCooldown(it, previous, rules.associateBy { r -> r.id }, now) }

        val occurrencesById = occurrences.associateBy { it.id }
        val reconciled = Planner.reconcile(previous, desired, now, occurrencesById)

        for (notification in reconciled) notificationRepo.upsert(notification)
        notificationRepo.pruneFiredBefore(now - NotificationRepo.FIRED_RETENTION)

        return reconciled
    }

    /** §13.3: "mute this event" -- a hidden rule with no way to ever fire on its own, whose only job is suppression. */
    private fun isMuteSuppressor(rule: Rule): Boolean =
        rule.schedule.leads.isEmpty() && !rule.schedule.notifyOnFirstSeen
}
