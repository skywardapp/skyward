package dev.fritze.skyward.alarm

import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry

/**
 * Runs [command] through `UiAutomation` and returns its stdout.
 *
 * The stream must be drained and closed, not merely opened: `am` and
 * `cmd appops` write their result and exit, and a reader that never consumes
 * it can return before the command has actually finished doing anything --
 * which reads as a mysteriously ineffective `appops set` two lines later.
 */
internal fun shell(command: String): String {
    val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
    return ParcelFileDescriptor.AutoCloseInputStream(pfd).bufferedReader().use { it.readText() }
}

/**
 * §10.2's three system-driven entry points are all `<protected-broadcast>`s
 * (`BOOT_COMPLETED`, `MY_PACKAGE_REPLACED`, and the exact-alarm permission
 * change). `ActivityManagerService` refuses those from any app-id outside
 * {root, system, phone, bluetooth, nfc, se, network_stack}, and it checks the
 * *action* before it resolves the component -- so neither an in-process
 * `sendBroadcast` naming our own receiver explicitly nor `am broadcast` from
 * the ordinary shell (uid 2000) gets through.
 *
 * `am instrument` inherits adbd's uid, so `adb root` in tools/ci/run-ui-tests.sh
 * is what makes this true on CI. It is best-effort by design: on a device where
 * adbd cannot be rooted the affected tests report themselves skipped rather
 * than failed, and [dev.fritze.skyward.alarm.SystemBroadcastReceiverTest]'s
 * manifest-resolution test still runs unconditionally so a skip can never
 * quietly mean "no coverage at all". See ADR 0018.
 *
 * Probed with `TIME_SET` rather than a real one of the three: it is equally
 * protected (so it answers the same question) but this app registers no
 * receiver for it, so the probe itself cannot perturb the state a test is
 * about to assert on.
 */
internal val canSendProtectedBroadcasts: Boolean by lazy {
    shell("am broadcast -a android.intent.action.TIME_SET").contains(BROADCAST_COMPLETED)
}

private const val BROADCAST_COMPLETED = "Broadcast completed"

/** Dispatches a protected [action] straight at [receiverClass]. Only valid when [canSendProtectedBroadcasts]. */
internal fun sendProtectedBroadcast(packageName: String, action: String, receiverClass: Class<*>): Boolean =
    shell("am broadcast -a $action -n $packageName/${receiverClass.name}").contains(BROADCAST_COMPLETED)
