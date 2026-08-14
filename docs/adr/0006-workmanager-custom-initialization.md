# ADR 0006: WorkManager uses a custom `Configuration.Provider`, not default init

**Status:** Accepted (implementation detail the design doc's `AlarmScheduler` sketch implies but doesn't spell out)

## Context

§10.2's approximate path schedules a one-off `WorkManager` job per
notification (`NotificationFireWorker`); §10.2's polling path schedules a
periodic job (`RefreshWorker`); the 14-day alarm-registration window (§10.2)
needs a daily top-up job (`AlarmWindowTopUpWorker`). All three need access
to `AppContainer` (repos, `AlarmScheduler`, `SourceRunner`) — there's no DI
framework in this app (§4.1: three Gradle modules, kept deliberately
simple), so that access has to come from a constructor parameter, not a
no-arg constructor.

WorkManager's default auto-initialization (via the `androidx.startup`
`InitializationProvider`, wired automatically by the `work-runtime-ktx`
manifest merge) only knows how to construct workers reflectively via the
standard two-argument `(Context, WorkerParameters)` constructor that
WorkManager 2.10.5 supplies itself.

## Decision

`SkywardApplication` implements `Configuration.Provider` and supplies a
`SkywardWorkerFactory` that constructs the three container-dependent
workers directly (falling back to WorkManager's default factory for
anything else). The manifest's `androidx.startup.InitializationProvider`
entry removes WorkManager's `WorkManagerInitializer` meta-data
(`tools:node="remove"`) so the two initialization paths don't race;
`WorkManager.initialize()` then runs implicitly the first time
`WorkManager.getInstance(context)` is called, using the app's
`Configuration.Provider`.

This is the standard AndroidX-documented pattern for on-demand
initialization with custom worker construction — not a Skyward-specific
workaround.
