package dev.fritze.skyward.desktop.scheduler

import dev.fritze.skyward.core.sources.SourceRunner
import kotlinx.coroutines.delay
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * The desktop counterpart of Android's periodic `skyward-refresh`
 * WorkManager job (§10.2) — same 15-minute floor, and like it forces
 * nothing: `SourceRunner.isDue` decides for every source, the COMPUTED ones
 * on the daily cadence of ADR 0009 and the POLLED ones on their own (§6.2).
 *
 * There is no OS-level scheduler behind it: while the app runs (window open
 * or hidden to tray, §10.3) this loop runs, and while it doesn't, nothing
 * refreshes — which is precisely why startup does a catch-up pass and shows
 * the "While you were away" panel. A source whose daily slot came and went
 * with the app closed is simply overdue, so that first pass picks it up.
 */
class SourceRefreshLoop(
    private val sourceRunner: SourceRunner,
    private val interval: Duration = DEFAULT_INTERVAL,
    private val clock: Clock = Clock.System,
) {

    /** Runs one pass, surfacing nothing: per-source failures are already isolated and diagnosed by [SourceRunner] (§6.2). */
    suspend fun runOnce() {
        sourceRunner.runDue(clock.now())
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
