package dev.fritze.skyward.core.sources

/**
 * §4.1: the source lists every frontend wires up identically -- shared
 * here so `AppContainer` and `DesktopContainer` build one set of
 * instances rather than each independently constructing the same two
 * lists. COMPUTED sources are pure local astronomy (no network) and
 * are force-run on a schedule by the caller; POLLED sources fetch from
 * NOAA/JPL/NASA (§7.3-§7.5) and derive their own due time via
 * `SourceRunner.isDue` (§6.2), so callers never force-run them.
 */
val defaultComputedSources: List<EventSource> =
    listOf(EclipseSource(), MeteorShowerSource(), MoonEventSource(), ConjunctionSource())

val defaultPolledSources: List<EventSource> = listOf(AuroraSource(), CometSource(), EonetSource())
