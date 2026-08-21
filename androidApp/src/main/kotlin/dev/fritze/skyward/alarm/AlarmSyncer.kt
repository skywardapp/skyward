package dev.fritze.skyward.alarm

import dev.fritze.skyward.core.model.NotificationStatus
import dev.fritze.skyward.core.model.PlannedNotification
import dev.fritze.skyward.core.persistence.NotificationRepo
import dev.fritze.skyward.core.persistence.OccurrenceRepo
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/**
 * §10.2: "for every PlannedNotification within the next 14 days, register
 * ...; alarms beyond 14 days are not registered with the OS ... the daily
 * WorkManager job tops up the 14-day window." Re-registers every row
 * inside the window on every sync (idempotent and cheap at this app's
 * scale) rather than tracking what's already registered, so a `fireAt`
 * that moved after a device timezone change (§10.4's own reconcile note)
 * can never leave a stale OS alarm behind.
 */
object AlarmSyncer {
    private val REGISTRATION_WINDOW = 14.days

    suspend fun sync(
        reconciled: List<PlannedNotification>,
        scheduler: AlarmScheduler,
        notificationRepo: NotificationRepo,
        occurrenceRepo: OccurrenceRepo,
        now: Instant,
    ) {
        val horizon = now + REGISTRATION_WINDOW
        val windowEnds = occurrenceWindowEnds(reconciled, occurrenceRepo, now)
        for (n in reconciled) {
            when (n.status) {
                NotificationStatus.PENDING, NotificationStatus.REGISTERED -> when {
                    // §10.4: past due *and* the occurrence's window has closed -- the
                    // reminder's moment is gone, so it becomes history. A past-due row
                    // whose window is still open deliberately falls through to the
                    // registration branch below instead: that is §10.4's "fires
                    // immediately on next planner run", and registering the row with
                    // its original past fireAt is exactly how it is expressed, since
                    // both AlarmManager and WorkManager run a trigger time in the past
                    // at once. (This branch used to swallow both cases, so a reminder
                    // for an eclipse still an hour away was marked MISSED on boot
                    // without ever firing -- issue #48.)
                    n.fireAt < now && !stillInWindow(n, windowEnds, now) -> {
                        scheduler.cancel(n.id)
                        notificationRepo.updateStatus(n.id, NotificationStatus.MISSED, firedAt = null)
                    }
                    n.fireAt <= horizon -> {
                        val precision = scheduler.schedule(n)
                        notificationRepo.updatePrecision(n.id, precision)
                        notificationRepo.updateStatus(n.id, NotificationStatus.REGISTERED, firedAt = null)
                    }
                    // Shouldn't normally happen (the window only shrinks toward `now`), but stay
                    // correct if a re-plan ever pushes a fireAt back out past it.
                    n.status == NotificationStatus.REGISTERED -> {
                        scheduler.cancel(n.id)
                        notificationRepo.updateStatus(n.id, NotificationStatus.PENDING, firedAt = null)
                    }
                }
                NotificationStatus.CANCELLED, NotificationStatus.MISSED -> scheduler.cancel(n.id)
                NotificationStatus.FIRED -> Unit
            }
        }
    }

    /**
     * Window ends for the overdue rows only — usually none at all, and rarely
     * more than a couple. Reading the whole table instead would deserialize
     * every `payload_json` (an eclipse carries its whole sampled `centralPath`,
     * §7.1.3) to look at one timestamp per row, and this runs inside
     * `BootReceiver`'s `goAsync()` budget.
     */
    private suspend fun occurrenceWindowEnds(
        reconciled: List<PlannedNotification>,
        occurrenceRepo: OccurrenceRepo,
        now: Instant,
    ): Map<String, Instant> {
        val overdue = reconciled
            .filter { (it.status == NotificationStatus.PENDING || it.status == NotificationStatus.REGISTERED) && it.fireAt < now }
            .mapTo(mutableSetOf()) { it.occurrenceId }
        return overdue.mapNotNull { id -> occurrenceRepo.getById(id)?.let { id to it.window.end } }.toMap()
    }

    /** An occurrence that has since been withdrawn (§6.3) has no window left to be inside. */
    private fun stillInWindow(n: PlannedNotification, windowEnds: Map<String, Instant>, now: Instant): Boolean {
        val end = windowEnds[n.occurrenceId] ?: return false
        return now <= end
    }
}
