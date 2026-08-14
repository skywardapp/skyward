package dev.fritze.skyward.desktop.tray

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.window.ApplicationScope
import com.kdroid.composetray.tray.api.Tray
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
 * Returns whether an icon was actually rendered, because background mode is
 * unusable without one: hiding the window with no tray to restore it from
 * would strand the process with no way back to it.
 */
@Composable
fun ApplicationScope.SkywardTray(actions: TrayActions): Boolean {
    // The library throws (or logs and no-ops) when StatusNotifierWatcher isn't
    // on the bus. Deciding once, at first composition, keeps a missing tray
    // from being retried on every recomposition.
    val trayAvailable = remember { runCatching { isTrayHostLikelyPresent() }.getOrDefault(false) }
    if (!trayAvailable) return false

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
 * A cheap pre-flight check. `DISPLAY`/`WAYLAND_DISPLAY` being absent means
 * there is no session to put an icon into at all — the case that actually
 * matters, since a headless run (CI, a `--background` launch from a script
 * with no session) is where an unguarded tray call would blow up rather than
 * degrade.
 */
private fun isTrayHostLikelyPresent(): Boolean =
    !System.getenv("WAYLAND_DISPLAY").isNullOrBlank() || !System.getenv("DISPLAY").isNullOrBlank()

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
