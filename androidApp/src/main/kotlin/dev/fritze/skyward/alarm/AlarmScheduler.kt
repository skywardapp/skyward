package dev.fritze.skyward.alarm

import dev.fritze.skyward.core.model.PlannedNotification
import dev.fritze.skyward.core.model.Precision

/**
 * §10.2. Both paths (exact `AlarmManager`, approximate `WorkManager`) live
 * behind this one interface so the planner never branches on it; it's also
 * the test seam (§17.5) — instrumented tests substitute a fake.
 */
interface AlarmScheduler {
    fun canScheduleExact(): Boolean

    /** Schedules [n] and returns the precision actually achieved. */
    fun schedule(n: PlannedNotification): Precision

    fun cancel(id: String)
}
