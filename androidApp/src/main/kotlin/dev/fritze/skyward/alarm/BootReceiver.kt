package dev.fritze.skyward.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.fritze.skyward.SkywardApplication
import kotlinx.coroutines.launch
import kotlin.time.Clock

/**
 * §10.2: "On BOOT_COMPLETED (and MY_PACKAGE_REPLACED): re-register the
 * window." A reboot (or app update) wipes every `AlarmManager` alarm, but
 * the desired set in the DB is still correct — this just re-syncs it onto
 * fresh OS alarms, no astronomy recomputation needed.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
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
