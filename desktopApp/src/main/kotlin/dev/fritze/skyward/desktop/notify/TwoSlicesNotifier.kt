package dev.fritze.skyward.desktop.notify

import com.sshtools.twoslices.Toast
import com.sshtools.twoslices.ToastType

/**
 * §10.3's first choice: `com.sshtools:two-slices`, which talks
 * `org.freedesktop.Notifications` over DBus on Linux.
 *
 * The only file in the app that imports the library (§19 R8) — if it ever
 * needs replacing, this class is the whole blast radius.
 */
class TwoSlicesNotifier : DesktopNotifier {

    override fun post(notification: DesktopNotification, onActivated: (String?) -> Unit): Boolean = try {
        Toast.builder()
            .type(ToastType.INFO)
            .title(notification.title)
            .content(notification.body)
            // "Clicking a notification raises the window on the relevant detail
            // view (DBus action if supported; else best effort)" (§10.3). On a
            // desktop whose notification daemon ignores actions this simply
            // never fires, which is exactly the specified degradation.
            .defaultAction { onActivated(notification.occurrenceId) }
            .toast()
        true
    } catch (e: Exception) {
        // ToasterException, but also a LinkageError-adjacent failure to find any
        // usable backend at all inside a sandbox. Either way this backend is out
        // and the chain should try `notify-send` (§10.3).
        System.err.println("two-slices notification failed (${e.message ?: e::class.simpleName}); falling back")
        false
    }
}
