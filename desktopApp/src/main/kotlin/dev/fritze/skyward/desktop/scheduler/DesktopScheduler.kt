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
import kotlin.time.Instant

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
    private val clock: Clock = Clock.System,
) {

    /**
     * §10.3: "on startup, list matches whose anchor passed while the app was
     * closed in a 'While you were away' panel instead of firing stale
     * notifications". Marks them MISSED and returns them for display.
     *
     * [preexistingIds] are the row ids that were already in the DB before this
     * session's startup re-plan. Anything outside that set was discovered
     * *just now* (a fresh aurora nowcast, say) and is a genuine new reminder
     * to fire, not something the user missed — without that distinction, a
     * `notifyOnFirstSeen` row created moments ago by the startup refresh
     * would be silently demoted into the panel, since the planner gives it a
     * `fireAt` of `now`.
     */
    suspend fun collectMissedWhileAway(now: Instant, preexistingIds: Set<String>): List<MissedReminder> {
        val stale = notificationRepo.getAll().filter {
            it.id in preexistingIds && it.fireAt <= now && it.status.isSchedulable()
        }
        val occurrences = occurrenceRepo.getAll().associateBy { it.id }
        for (notification in stale) {
            notificationRepo.updateStatus(notification.id, NotificationStatus.MISSED, firedAt = null)
        }
        return stale
            .sortedByDescending { it.fireAt }
            .map { MissedReminder(it.copy(status = NotificationStatus.MISSED), occurrences[it.occurrenceId]) }
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
