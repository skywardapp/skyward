package dev.fritze.skyward.desktop.tray

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.window.ApplicationScope
import com.kdroid.composetray.tray.api.Tray
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The only file that touches ComposeNativeTray (§19 R8: "both isolated
 * behind tiny interfaces"). Everything the rest of the app needs to say to
 * the tray is a [TrayActions] value; if the library ever has to be swapped
 * or dropped, this file is the entire blast radius.
 *
 * §10.3 picks ComposeNativeTray over Compose's built-in AWT `Tray` because
 * the latter is broken on GNOME; this one speaks StatusNotifierItem /
 * AppIndicator over DBus.
 */
data class TrayActions(
    val onOpen: () -> Unit,
    val onRefresh: () -> Unit,
    val onQuit: () -> Unit,
    val tooltip: String = "Skyward",
)

/**
 * Renders the tray icon, or nothing at all if no status-notifier host is
 * available — §10.3's specified "no-tray degradation path". A desktop
 * without a tray must still run the app; it just loses the shortcut.
 *
 * Returns whether an icon was actually rendered — null while that is still
 * being determined. Callers need this because background mode is unusable
 * without an icon: hiding the window with no tray to restore it from would
 * strand the process with no way back to it.
 */
@Composable
fun ApplicationScope.SkywardTray(actions: TrayActions): Boolean? {
    // Asked once per process, off the composition thread: the check shells out
    // to `gdbus`, and a session bus that never answers would otherwise freeze
    // the first frame for as long as the timeout. Null until it answers, so
    // nobody acts on a guess.
    val trayAvailable by produceState<Boolean?>(initialValue = null) {
        value = withContext(Dispatchers.IO) { runCatching { isTrayHostLikelyPresent() }.getOrDefault(false) }
    }
    if (trayAvailable != true) return trayAvailable

    // No try/catch around the call itself: it is a composable, and the library
    // does its actual DBus work inside a LaunchedEffect where a throw would not
    // reach us anyway. The availability check above is the guard that matters —
    // it is the headless case that would otherwise fail hard rather than
    // degrade (§10.3's no-tray path).
    Tray(
        iconContent = { TrayIcon() },
        tooltip = actions.tooltip,
        primaryAction = actions.onOpen,
        menuContent = {
            Item("Open Skyward") { actions.onOpen() }
            Item("Refresh now") { actions.onRefresh() }
            Divider()
            Item("Quit") { actions.onQuit() }
        },
    )
    return true
}

/**
 * Whether an icon put in the tray would actually appear somewhere.
 *
 * The answer gates more than the icon: background mode hides the window and
 * relies on the tray to bring it back, so a wrong "yes" here strands the
 * process with no reachable UI. A `DISPLAY` check alone is not enough for
 * that — a bare X server (Xvfb, a minimal WM, a session whose panel died)
 * has a display and no status-notifier host at all.
 *
 * So: no session, no tray; otherwise ask the session bus who owns
 * `org.kde.StatusNotifierWatcher`, which is the name both StatusNotifierItem
 * hosts and AppIndicator implementations register. `gdbus` is glib, always
 * present in `org.freedesktop.Platform` — the same reasoning as §10.3's
 * `notify-send` fallback, and it keeps a DBus client library out of the
 * build (§19 R8).
 */
private fun isTrayHostLikelyPresent(): Boolean {
    val hasSession = !System.getenv("WAYLAND_DISPLAY").isNullOrBlank() || !System.getenv("DISPLAY").isNullOrBlank()
    if (!hasSession) return false
    // Unknown (no gdbus, no session bus, a timeout) is treated as "no tray":
    // losing the icon degrades gracefully, whereas hiding the window into a
    // tray that isn't there does not.
    return dbusNameHasOwner(STATUS_NOTIFIER_WATCHER) == true
}

private const val STATUS_NOTIFIER_WATCHER = "org.kde.StatusNotifierWatcher"
private const val DBUS_QUERY_TIMEOUT_SECONDS = 3L

/** Null when the question could not be asked at all, as opposed to answered "no". */
private fun dbusNameHasOwner(name: String): Boolean? = try {
    val process = ProcessBuilder(
        "gdbus", "call", "--session",
        "--dest", "org.freedesktop.DBus",
        "--object-path", "/org/freedesktop/DBus",
        "--method", "org.freedesktop.DBus.NameHasOwner",
        name,
    ).redirectErrorStream(true).start()

    if (!process.waitFor(DBUS_QUERY_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        process.destroy()
        null
    } else {
        // Prints "(true,)" or "(false,)"; anything else means it never got to ask.
        val output = process.inputStream.bufferedReader().readText().trim()
        when {
            process.exitValue() != 0 -> null
            output.startsWith("(true") -> true
            output.startsWith("(false") -> false
            else -> null
        }
    }
} catch (e: Exception) {
    if (e is InterruptedException) Thread.currentThread().interrupt()
    null
}

/**
 * Drawn rather than shipped as a PNG: the icon has to look right against
 * both light and dark panels, and a single flat-color glyph does that
 * without a second asset. A crescent with a star — the app's subject in the
 * smallest number of strokes.
 */
@Composable
private fun TrayIcon() {
    Canvas(Modifier.fillMaxSize()) {
        val color = Color(0xFFE9EEF7)
        val cx = size.width / 2f
        val cy = size.height / 2f
        val radius = size.minDimension * 0.38f

        val crescent = Path().apply {
            addOval(androidx.compose.ui.geometry.Rect(cx - radius, cy - radius, cx + radius, cy + radius))
        }
        val bite = Path().apply {
            val offset = radius * 0.55f
            addOval(
                androidx.compose.ui.geometry.Rect(
                    cx - radius + offset,
                    cy - radius - offset * 0.2f,
                    cx + radius + offset,
                    cy + radius - offset * 0.2f,
                ),
            )
        }
        drawPath(
            path = Path().apply { op(crescent, bite, androidx.compose.ui.graphics.PathOperation.Difference) },
            color = color,
        )
        drawStar(center = Offset(cx - radius * 0.55f, cy - radius * 0.75f), outerRadius = radius * 0.30f, color = color)
    }
}

private fun DrawScope.drawStar(center: Offset, outerRadius: Float, color: Color) {
    val innerRadius = outerRadius * 0.42f
    val path = Path()
    for (i in 0 until 10) {
        val radius = if (i % 2 == 0) outerRadius else innerRadius
        val angle = -PI / 2 + i * PI / 5
        val x = center.x + (radius * cos(angle)).toFloat()
        val y = center.y + (radius * sin(angle)).toFloat()
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    drawPath(path, color)
}
