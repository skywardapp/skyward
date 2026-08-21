package dev.fritze.skyward.desktop

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.fritze.skyward.desktop.data.DesktopContainer
import dev.fritze.skyward.desktop.scheduler.DesktopScheduler
import dev.fritze.skyward.desktop.scheduler.SourceRefreshLoop
import dev.fritze.skyward.desktop.tray.SkywardTray
import dev.fritze.skyward.desktop.tray.TrayActions
import dev.fritze.skyward.desktop.ui.DesktopAppState
import dev.fritze.skyward.desktop.ui.SkywardApp
import dev.fritze.skyward.desktop.util.runCatchingCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlin.time.Clock

/** Shown in the About section and written into §12 export files. Kept in step with `compose.desktop`'s `packageVersion`. */
const val APP_VERSION = "0.1.0"

/**
 * §14: single window, ~1280×800 default, left nav rail. §10.3: with
 * "Background mode" enabled, closing the window hides to tray instead of
 * exiting, and the in-process scheduler keeps running behind it.
 *
 * `debug-matches` keeps M2's CLI acceptance path working (§18) — it must
 * stay ahead of any windowing setup so it still runs headless.
 */
fun main(args: Array<String>) {
    if (args.firstOrNull() == "debug-matches") {
        runDebugMatches()
        return
    }

    val container = DesktopContainer.open()
    val state = DesktopAppState(container, container.applicationScope)
    val windowVisibility = MutableStateFlow(!args.contains(FLAG_BACKGROUND))

    val scheduler = DesktopScheduler(
        notificationRepo = container.notificationRepo,
        occurrenceRepo = container.occurrenceRepo,
        notifier = state.notifier,
        onActivated = { occurrenceId ->
            // §10.3: "Clicking a notification raises the window on the relevant
            // detail view (DBus action if supported; else best effort)."
            windowVisibility.value = true
            occurrenceId?.let(state::openOccurrence)
        },
    )
    val refreshLoop = SourceRefreshLoop(
        sourceRunner = container.sourceRunner,
        forcedSourceIds = container.computedSources.mapTo(mutableSetOf()) { it.id },
    )

    container.applicationScope.launch { startBackgroundWork(container, state, scheduler, refreshLoop) }

    application {
        val visible by windowVisibility.collectAsState()
        val settings by state.settings.collectAsState()
        val backgroundMode = settings[DesktopContainer.KEY_BACKGROUND_MODE] == "true"

        val quit: () -> Unit = {
            container.close()
            exitApplication()
        }

        val trayAvailable = SkywardTray(
            TrayActions(
                onOpen = { windowVisibility.value = true },
                onRefresh = state::refreshEverything,
                onQuit = quit,
            ),
        )

        // `--background` starts hidden, but whether there is a tray to restore
        // the window from is only known once the check has answered (null until
        // then). Without one, staying hidden would leave a running process with
        // no window and no icon — unreachable. Showing the window is the
        // degradation; the reminders it was started for keep working either way.
        LaunchedEffect(trayAvailable) {
            if (trayAvailable == false) windowVisibility.value = true
        }

        val windowState = rememberWindowState(
            position = WindowPosition.Aligned(Alignment.Center),
            size = DpSize(1280.dp, 800.dp),
        )
        Window(
            // Background mode without a tray icon would hide the window with
            // nothing left to restore it from, so the tray's absence overrides
            // the setting (§10.3's no-tray degradation path).
            onCloseRequest = { if (backgroundMode && trayAvailable == true) windowVisibility.value = false else quit() },
            title = "Skyward",
            state = windowState,
            visible = visible,
        ) {
            SkywardApp(state)
        }
    }
}

private const val FLAG_BACKGROUND = "--background"

/**
 * The startup sequence, in the one order that works: seed defaults, load the
 * persisted OVATION grid, snapshot the notification table, re-plan against the
 * current DB, *then* collect what the re-plan judged missed while the app was
 * closed — and only after that let the scheduler start firing. Reversing any
 * two of these either fires a stale reminder (§10.3 forbids exactly that) or
 * hides a fresh one in the missed panel.
 *
 * The snapshot has to be taken before the re-plan because the re-plan is what
 * applies §10.4 (overdue but still inside the occurrence's window → fire now;
 * window closed → MISSED); comparing against it is how a row the *user* missed
 * is told apart from one this very startup created.
 *
 * The preparation is allowed to fail without taking the background services
 * with it. A planner that throws on one bad rule would otherwise leave the
 * app running with no scheduler and no refresh loop at all — silently not
 * notifying, which is the one failure mode a reminder app cannot have.
 */
private suspend fun startBackgroundWork(
    container: DesktopContainer,
    state: DesktopAppState,
    scheduler: DesktopScheduler,
    refreshLoop: SourceRefreshLoop,
) {
    runCatchingCancellable {
        container.ensureDefaultRulesSeeded()
        state.reloadOvationGrid()

        val beforeReplan = container.notificationRepo.getAll()
        container.replan(Clock.System.now())
        state.setMissedWhileAway(scheduler.collectMissedWhileAway(beforeReplan))
    }.onFailure { System.err.println("startup preparation failed: ${it.message ?: it::class.simpleName}") }

    container.applicationScope.launch { scheduler.run() }
    container.applicationScope.launch { refreshLoop.run() }
}
