package dev.fritze.skyward.desktop.notify

import com.sshtools.twoslices.Toast
import com.sshtools.twoslices.ToastType
import com.sshtools.twoslices.ToasterFactory

/**
 * §10.3's first choice: `com.sshtools:two-slices`.
 *
 * The only file in the app that imports the library (§19 R8) — if it ever
 * needs replacing, this class is the whole blast radius.
 *
 * two-slices picks its own backend at runtime from what it can find. Note
 * that its `org.freedesktop.Notifications` DBus backend needs
 * `com.github.hypfvieh:dbus-java-core`, which two-slices declares as an
 * *optional* dependency and this app therefore does not resolve — so in the
 * shipped build two-slices reaches the desktop by spawning `notify-send`,
 * the same mechanism [NotifySendNotifier] uses, rather than over DBus.
 *
 * @param chosenToasterClassName Which backend two-slices picks, by class
 * name. Defaults to the real lookup; a test overrides it to force the
 * console-only rejection path deterministically, since which backend the
 * *real* factory picks depends on what happens to be installed on the
 * machine running the test.
 */
class TwoSlicesNotifier(
    private val chosenToasterClassName: () -> String = { ToasterFactory.getFactory().toaster()::class.java.name },
) : DesktopNotifier {

    override fun post(notification: DesktopNotification, onActivated: (String?) -> Unit): Boolean = try {
        if (!reachesTheDesktop()) {
            System.err.println("two-slices found no desktop notification backend; falling back")
            false
        } else {
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
        }
    } catch (e: Exception) {
        // ToasterException, but also a LinkageError-adjacent failure to find any
        // usable backend at all inside a sandbox. Either way this backend is out
        // and the chain should try `notify-send` (§10.3).
        System.err.println("two-slices notification failed (${e.message ?: e::class.simpleName}); falling back")
        false
    }

    /**
     * two-slices always finds *a* toaster: when no desktop backend is
     * available it settles on `SysOutToaster`, which prints
     * `[!] title - body` to stdout and reports success. That is the one
     * answer this class must never pass on. [FallbackChainNotifier] would
     * remember it as the working backend and stop trying `notify-send`, and
     * the scheduler would record every reminder FIRED while the user saw
     * nothing — silently losing reminders is precisely what §10.3's fallback
     * chain exists to prevent.
     *
     * Matched on class name because two-slices reports no capability that
     * separates a real backend from the console one (`capabilities()` is
     * empty for both). If the class is ever renamed this check stops
     * matching and behaviour returns to what it was before this guard, which
     * is the right direction for a guard like this to fail in.
     */
    private fun reachesTheDesktop(): Boolean =
        chosenToasterClassName() !in CONSOLE_ONLY_TOASTERS

    companion object {
        val CONSOLE_ONLY_TOASTERS = setOf("com.sshtools.twoslices.impl.SysOutToaster")
    }
}
