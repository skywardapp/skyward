package dev.fritze.skyward.desktop.notify

/**
 * One reminder, ready to hand to the desktop environment. Deliberately
 * library-free: §19 R8 requires `two-slices` to sit behind "a tiny
 * interface", and this is it — everything above this file talks in terms of
 * this type and [DesktopNotifier], never `com.sshtools.twoslices`.
 */
data class DesktopNotification(
    val title: String,
    val body: String,
    /** Passed back to the activation callback so the window can open the right detail view (§10.3). */
    val occurrenceId: String?,
)

fun interface DesktopNotifier {
    /**
     * Posts [notification]. Returns false if this backend could not deliver
     * it at all, so a chain can try the next one — a thrown exception counts
     * as "could not deliver" and must not escape.
     *
     * [onActivated] is invoked with [DesktopNotification.occurrenceId] if the
     * user clicks the notification and the backend supports actions at all;
     * §10.3 explicitly calls this best-effort.
     */
    fun post(notification: DesktopNotification, onActivated: (String?) -> Unit): Boolean
}

/**
 * §10.3's stated implementation order: "try `com.sshtools:two-slices`; if it
 * fights the Flatpak sandbox, fall back to spawning `notify-send`".
 *
 * Once a backend delivers successfully it is remembered, so a broken first
 * choice costs one failed attempt per process rather than one per reminder —
 * and, more importantly, reminders don't silently arrive through two
 * different mechanisms depending on transient failures.
 */
class FallbackChainNotifier(private val backends: List<DesktopNotifier>) : DesktopNotifier {
    private var chosen: DesktopNotifier? = null

    override fun post(notification: DesktopNotification, onActivated: (String?) -> Unit): Boolean {
        chosen?.let { return it.post(notification, onActivated) }
        for (backend in backends) {
            if (backend.post(notification, onActivated)) {
                chosen = backend
                return true
            }
        }
        return false
    }

    companion object {
        /** The §10.3 chain: DBus via two-slices, then `notify-send` (present in `org.freedesktop.Platform`). */
        fun default(): DesktopNotifier = FallbackChainNotifier(listOf(TwoSlicesNotifier(), NotifySendNotifier()))
    }
}
