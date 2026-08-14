package dev.fritze.skyward.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.fritze.skyward.SkywardApplication
import kotlinx.coroutines.launch
import kotlin.time.Clock

/**
 * §10.2: "Listen for ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED:
 * revocation cancels all exact alarms -> immediately re-plan everything
 * onto the approximate path; a later grant re-plans back." A plain
 * [AlarmSyncer.sync] pass is enough either way -- it already re-derives
 * EXACT vs APPROXIMATE from `AlarmScheduler.canScheduleExact()` on every
 * row it touches, so it "re-plans" onto whichever path is now correct
 * without needing to know which direction the permission moved.
 */
class ExactAlarmPermissionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val container = (context.applicationContext as SkywardApplication).container
        val pendingResult = goAsync()
        container.applicationScope.launch {
            try {
                val reconciled = container.notificationRepo.getAll()
                AlarmSyncer.sync(reconciled, container.alarmScheduler, container.notificationRepo, Clock.System.now())
            } finally {
                pendingResult.finish()
            }
        }
    }
}
