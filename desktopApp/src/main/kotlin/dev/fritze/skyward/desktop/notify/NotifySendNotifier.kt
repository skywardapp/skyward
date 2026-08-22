package dev.fritze.skyward.desktop.notify

import java.util.concurrent.TimeUnit

/**
 * §10.3's fallback: spawn `notify-send`, which is present in
 * `org.freedesktop.Platform` and therefore always available inside the
 * Flatpak sandbox even when the in-process DBus path doesn't work.
 *
 * `notify-send` has no way to report a click back to us, so activation is
 * simply not supported here — §10.3 already calls click-to-raise best-effort.
 */
class NotifySendNotifier(private val command: String = "notify-send") : DesktopNotifier {

    override fun post(notification: DesktopNotification, onActivated: (String?) -> Unit): Boolean = try {
        val process = ProcessBuilder(
            command,
            "--app-name=Skyward",
            "--icon=dev.fritze.Skyward",
            // A body starting with "-" would otherwise be parsed as an option.
            "--",
            notification.title,
            // Remote strings reach this body (§7.4/§7.5) and notification
            // daemons parse markup in it -- see [escapeNotificationBodyMarkup].
            escapeNotificationBodyMarkup(notification.body),
        )
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .start()

        // A notification daemon that never answers must not wedge the scheduler
        // loop; treat a hung `notify-send` as a failed delivery.
        if (process.waitFor(NOTIFY_SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.exitValue() == 0
        } else {
            process.destroy()
            false
        }
    } catch (e: Exception) {
        // IOException when the binary isn't on PATH at all; InterruptedException
        // if the scheduler is being shut down mid-post.
        if (e is InterruptedException) Thread.currentThread().interrupt()
        System.err.println("notify-send failed (${e.message ?: e::class.simpleName})")
        false
    }

    private companion object {
        const val NOTIFY_SEND_TIMEOUT_SECONDS = 5L
    }
}
