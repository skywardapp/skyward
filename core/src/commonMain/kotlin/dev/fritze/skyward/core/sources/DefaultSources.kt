package dev.fritze.skyward.core.sources

/**
 * §4.1: the source lists every frontend wires up identically -- shared
 * here so `AppContainer` and `DesktopContainer` build one set of
 * instances rather than each independently constructing the same two
 * lists. COMPUTED sources are local astronomy (no network); POLLED
 * sources fetch from NOAA/JPL/NASA (§7.3-§7.5). Both derive their own
 * due time via `SourceRunner.isDue` (§6.2) — the split survives because
 * callers still force the COMPUTED ones on a horizon or settings change,
 * and because a periodic pass must never be able to spend its whole
 * budget on them before reaching the POLLED ones (issue #49, ADR 0009).
 */
val defaultComputedSources: List<EventSource> =
    listOf(EclipseSource(), MeteorShowerSource(), MoonEventSource(), ConjunctionSource())

val defaultPolledSources: List<EventSource> = listOf(AuroraSource(), CometSource(), EonetSource())
