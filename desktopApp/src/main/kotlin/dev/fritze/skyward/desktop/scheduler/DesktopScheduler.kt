package dev.fritze.skyward.desktop.scheduler

import dev.fritze.skyward.core.model.NotificationStatus
import dev.fritze.skyward.core.model.Occurrence
import dev.fritze.skyward.core.model.PlannedNotification
import dev.fritze.skyward.core.persistence.NotificationRepo
import dev.fritze.skyward.core.persistence.OccurrenceRepo
import dev.fritze.skyward.desktop.notify.DesktopNotification
import dev.fritze.skyward.desktop.notify.DesktopNotifier
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

/** One entry of the §10.3 "While you were away" panel. */
data class MissedReminder(
    val notification: PlannedNotification,
    /** Null if the occurrence itself has since been withdrawn (§6.3) — the copy is still worth showing. */
    val occurrence: Occurrence?,
)

/**
 * §10.3's in-process scheduler: "a coroutine loop (`delay` until next due
 * item, recompute on DB change via Flow)". Desktop has no OS alarm layer, so
 * the `planned_notification` table *is* the schedule — [run] watches it and
 * fires each row as it comes due.
 */
class DesktopScheduler(
    private val notificationRepo: NotificationRepo,
    private val occurrenceRepo: OccurrenceRepo,
    private val notifier: DesktopNotifier,
    private val onActivated: (String?) -> Unit,
    /**
     * Called with the outcome of every delivery attempt (§10.1's honesty
     * contract). When every backend in the chain refuses, the reminder is
     * gone and the only party that can tell the user is the window — see
     * `DesktopAppState.recordDeliveryOutcome`. Reported both ways so a
     * later success can retract the claim rather than leaving a warning up
     * after the notification daemon comes back.
     */
    private val onDeliveryOutcome: (delivered: Boolean) -> Unit = {},
    private val clock: Clock = Clock.System,
) {

    /**
     * §10.3: "on startup, list matches whose anchor passed while the app was
     * closed in a 'While you were away' panel instead of firing stale
     * notifications" — the display half of §10.4's catch-up rule.
     *
     * The classification is the startup re-plan's job, not this method's: only
     * `Planner.reconcile` knows each overdue row's occurrence window, and
     * §10.4 draws the line there. A row whose event is still happening stays
     * PENDING and [run] fires it at once (it is overdue, not stale); a row
     * whose window has closed comes back MISSED, and those are what this
     * panel lists. Deciding it here instead — "past due, therefore missed" —
     * is what left the panel empty in its target case while silently swallowing
     * reminders for events that had not even started yet (issue #48).
     *
     * [beforeReplan] is the notification table as it stood *before* this
     * session's startup re-plan, which is what makes "the user missed this"
     * distinguishable from "the re-plan just created this": a row discovered
     * moments ago (a fresh aurora nowcast, say) is a genuine new reminder to
     * fire, and a row already MISSED in an earlier session is old history that
     * must not resurface in the panel every launch.
     */
    suspend fun collectMissedWhileAway(beforeReplan: List<PlannedNotification>): List<MissedReminder> {
        val wasSchedulable = beforeReplan.filter { it.status.isSchedulable() }.mapTo(mutableSetOf()) { it.id }
        val missed = notificationRepo.getAll().filter { it.id in wasSchedulable && it.status == NotificationStatus.MISSED }
        val occurrences = occurrenceRepo.getAll().associateBy { it.id }
        return missed
            .sortedByDescending { it.fireAt }
            .map { MissedReminder(it, occurrences[it.occurrenceId]) }
    }

    /**
     * Runs until cancelled. Re-derives the next due item on every DB change,
     * so a re-plan (§9.7) is picked up immediately rather than at the end of
     * whatever `delay` was already in flight.
     */
    suspend fun run() {
        notificationRepo.observeAll().collectLatest { all ->
            val schedulable = all.filter { it.status.isSchedulable() }
            while (true) {
                val now = clock.now()
                val due = schedulable.filter { it.fireAt <= now }
                if (due.isNotEmpty()) {
                    // Firing writes to the DB, which re-emits and cancels this
                    // block via collectLatest — hence NonCancellable around the
                    // post-and-record pair, so a reminder can never be shown to
                    // the user without being recorded as FIRED (which would show
                    // it again on the next emission). Remaining due rows are
                    // picked up by the re-emission this very write triggers.
                    withContext(NonCancellable) { for (notification in due) fire(notification) }
                    return@collectLatest
                }
                val next = schedulable.minOfOrNull { it.fireAt } ?: break
                // A tiny floor keeps a clock that jitters backwards across the
                // fireAt boundary from spinning this loop.
                delay(maxOf(next - now, MIN_SLEEP))
            }
            awaitCancellation()
        }
    }

    private suspend fun fire(notification: PlannedNotification) {
        // Desktop reminders are always EXACT (§10.1: the honesty hedge exists
        // for Android's inexact-alarm fallback; an in-process `delay` has no
        // such degradation), so the stored body is fired verbatim — no
        // `applyApproximateHedge` here.
        val posted = notifier.post(
            DesktopNotification(notification.title, notification.body, notification.occurrenceId),
            onActivated,
        )
        if (!posted) {
            System.err.println("no desktop notifier could deliver reminder ${notification.id}")
        }
        // stderr is nobody's notification surface: a user whose desktop has no
        // working notification daemon would otherwise learn nothing at all,
        // and the reminder is still marked FIRED below (#79).
        onDeliveryOutcome(posted)
        // Recorded as FIRED either way: a reminder the desktop refused to show
        // is still a reminder whose moment has passed, and re-posting it on
        // every subsequent DB change would be worse than losing it once.
        notificationRepo.updateStatus(notification.id, NotificationStatus.FIRED, firedAt = clock.now())
    }

    private fun NotificationStatus.isSchedulable() = this == NotificationStatus.PENDING || this == NotificationStatus.REGISTERED

    private companion object {
        val MIN_SLEEP = 1.seconds
    }
}
