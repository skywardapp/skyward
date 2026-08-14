package dev.fritze.skyward.desktop.scheduler

import dev.fritze.skyward.core.sources.SourceRunner
import kotlinx.coroutines.delay
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * The desktop counterpart of Android's periodic `skyward-refresh`
 * WorkManager job (§10.2) — same 15-minute floor, same "force the COMPUTED
 * sources every pass so the rolling horizon window keeps moving, and let
 * `SourceRunner.isDue` decide for the POLLED ones" split.
 *
 * There is no OS-level scheduler behind it: while the app runs (window open
 * or hidden to tray, §10.3) this loop runs, and while it doesn't, nothing
 * refreshes — which is precisely why startup does a catch-up pass and shows
 * the "While you were away" panel.
 */
class SourceRefreshLoop(
    private val sourceRunner: SourceRunner,
    private val forcedSourceIds: Set<String>,
    private val interval: Duration = DEFAULT_INTERVAL,
    private val clock: Clock = Clock.System,
) {

    /** Runs one pass, surfacing nothing: per-source failures are already isolated and diagnosed by [SourceRunner] (§6.2). */
    suspend fun runOnce() {
        sourceRunner.runDue(clock.now(), force = forcedSourceIds)
    }

    /** Runs until cancelled, starting with an immediate pass. */
    suspend fun run() {
        while (true) {
            runOnce()
            delay(interval)
        }
    }

    private companion object {
        val DEFAULT_INTERVAL = 15.minutes
    }
}
