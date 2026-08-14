package dev.fritze.skyward.alarm

import dev.fritze.skyward.core.model.PlannedNotification
import dev.fritze.skyward.core.model.Precision

/** §17.5's test seam: substitutes [AndroidAlarmScheduler] so instrumented tests never touch real `AlarmManager`/`WorkManager` scheduling. */
class FakeAlarmScheduler(private var canScheduleExact: Boolean = true) : AlarmScheduler {
    val scheduled = mutableListOf<PlannedNotification>()
    val cancelled = mutableListOf<String>()

    override fun canScheduleExact(): Boolean = canScheduleExact

    fun setCanScheduleExact(value: Boolean) {
        canScheduleExact = value
    }

    override fun schedule(n: PlannedNotification): Precision {
        scheduled += n
        return if (canScheduleExact) Precision.EXACT else Precision.APPROXIMATE
    }

    override fun cancel(id: String) {
        cancelled += id
    }
}
