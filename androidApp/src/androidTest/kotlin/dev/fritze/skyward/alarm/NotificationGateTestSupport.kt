package dev.fritze.skyward.alarm

import android.content.Context
import dev.fritze.skyward.data.AppContainer

/**
 * §17.5's seam for [NotificationGate]. Neither the runtime grant nor the
 * app-level notification toggle can be flipped from inside an instrumented
 * test, so suites pin the state they mean to exercise.
 *
 * [AppContainer] lives for the whole test process, so every suite that pins
 * a gate owes an `@After` [restoreRealNotificationGate] -- otherwise a fake
 * leaks into whichever class the runner picks next and quietly turns its
 * assertions into no-ops. Shared so that contract is written down once.
 */
internal fun AppContainer.allowNotifications() {
    notificationGate = NotificationGate { true }
}

internal fun AppContainer.blockNotifications() {
    notificationGate = NotificationGate { false }
}

internal fun AppContainer.restoreRealNotificationGate(context: Context) {
    notificationGate = AndroidNotificationGate(context)
}
