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
        // The action is a protected broadcast, so no other app can *broadcast* it -- but this
        // receiver has to be exported to hear the system, and an exported receiver can still be
        // targeted by an explicit intent (by component name) carrying any action at all. Without
        // this guard any installed app could poke us into a full alarm re-sync at will.
        if (intent.action != ACTION_EXACT_ALARM_PERMISSION_STATE_CHANGED) return
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

/**
 * `AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED` as a literal: the constant
 * itself is API 31+, and this must stay readable on minSdk 26 (where the filter simply never
 * matches). Kept in sync with the intent-filter in AndroidManifest.xml.
 */
private const val ACTION_EXACT_ALARM_PERMISSION_STATE_CHANGED =
    "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED"
