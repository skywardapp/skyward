package dev.fritze.skyward.alarm

import dev.fritze.skyward.core.model.NotificationStatus
import dev.fritze.skyward.core.model.PlannedNotification
import dev.fritze.skyward.core.persistence.NotificationRepo
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

    suspend fun sync(reconciled: List<PlannedNotification>, scheduler: AlarmScheduler, notificationRepo: NotificationRepo, now: Instant) {
        val horizon = now + REGISTRATION_WINDOW
        for (n in reconciled) {
            when (n.status) {
                NotificationStatus.PENDING, NotificationStatus.REGISTERED -> {
                    if (n.fireAt < now) {
                        // Already past due by the time this sync ran (e.g. boot recovery after
                        // the device was off past fireAt) -- don't register an alarm that would
                        // fire immediately; the user already missed the window.
                        scheduler.cancel(n.id)
                        notificationRepo.updateStatus(n.id, NotificationStatus.MISSED, firedAt = null)
                    } else if (n.fireAt <= horizon) {
                        val precision = scheduler.schedule(n)
                        notificationRepo.updatePrecision(n.id, precision)
                        notificationRepo.updateStatus(n.id, NotificationStatus.REGISTERED, firedAt = null)
                    } else if (n.status == NotificationStatus.REGISTERED) {
                        // Shouldn't normally happen (the window only shrinks toward `now`), but
                        // stay correct if a re-plan ever pushes a fireAt back out past it.
                        scheduler.cancel(n.id)
                        notificationRepo.updateStatus(n.id, NotificationStatus.PENDING, firedAt = null)
                    }
                }
                NotificationStatus.CANCELLED, NotificationStatus.MISSED -> scheduler.cancel(n.id)
                NotificationStatus.FIRED -> Unit
            }
        }
    }
}
