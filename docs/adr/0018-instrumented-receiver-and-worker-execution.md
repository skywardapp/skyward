# ADR 0018: §17.5's receivers and workers are executed, by the four routes the OS actually allows

**Status:** Accepted (deviates from §17.5's prescribed mechanism, not from what it asks to be true)

## Context

§17.5 spells out how the Android instrumented tests are to work:

> alarm registers & receiver posts notification (substitute a fake
> `AlarmScheduler` per §10.2 and **fire the receiver directly**; assert via
> `NotificationManager`)

The suite written to that sentence did substitute the fake, but the second
half was never done. Instead each test re-implemented the receiver's body
inline — "Simulate what `NotificationAlarmReceiver` does…", "Mirrors
`BootReceiver.onReceive`…". The result (#55) was that the app's entire
delivery mechanism was executed by no test at all:
`AndroidAlarmScheduler`, all three `BroadcastReceiver`s, all three
`CoroutineWorker`s and `SkywardWorkerFactory`. A wrong action prefix in
`AlarmIntents`, a receiver dropped from the manifest, a typo in the worker
factory's class-name routing, or a `goAsync()` that never finishes would
each mean silently missed reminders on a real device, with the suite green.

Two further gaps followed from the same shape. The CI matrix ran API 30
only, where `canScheduleExactAlarms()` does not exist and
`AndroidAlarmScheduler` hardwires `canScheduleExact()` to true — so §17.5's
denied-permission scenario, the one it says "must not be an afterthought"
because it is the `play` flavour's default state, was only ever produced by
the fake. And §17.5's SAF export/import round-trip, deferred out of M3 by
its own accept criteria and owed by M5, was never written.

Fixing this runs into four separate OS restrictions, each of which allows
exactly one route:

1. **`goAsync()` cannot be called outside a system dispatch.** It returns
   the `BroadcastReceiver`'s pending result, which only `ActivityThread`
   populates. Called from a direct `onReceive(...)` it returns null, and the
   `finally { pendingResult.finish() }` in all three receivers then throws
   inside `applicationScope` — a `SupervisorJob` with no exception handler,
   so the failure takes the test process down rather than the test. §17.5's
   "fire the receiver directly" is therefore not literally possible.
2. **`BOOT_COMPLETED` and `MY_PACKAGE_REPLACED` are protected broadcasts.**
   `ActivityManagerService` gates them on the calling app-id being in
   {root, system, phone, bluetooth, nfc, se, network\_stack}, checked on the
   *action* before the component is resolved — so an explicit same-app
   `sendBroadcast` naming our own receiver is refused too. And
   `UiAutomation.executeShellCommand` runs as uid 2000, which is not in that
   set, so plain `am broadcast` is refused as well.
3. **`USE_EXACT_ALARM` outranks the app-op.** `canScheduleExactAlarms()`
   returns true for a package holding it without consulting app-ops at all.
   The `foss` flavour holds it from API 33 (§10.2, D13), so on foss at 33+
   the denied state is not producible by any means.
4. **The SAF picker is a system Activity.** Driving it by UI would be the
   flakiest thing in the repo, and it is not the part of the path worth
   testing — `SyncScreen`'s launchers, `SyncUiController`'s `ContentResolver`
   IO and `SyncViewModel` are.

Separately, `androidx.work.testing.WorkManagerTestInitHelper` — the obvious
tool for the workers — is unusable here, and ADR 0006 is why:
`AppContainer.scheduleBackgroundWork()` calls `WorkManager.getInstance()`
from `Application.onCreate`, so WorkManager is always already initialised
before a test runs and `initializeTestWorkManager()` throws. Making it
usable would mean a custom `AndroidJUnitRunner`, a test `Application`
subclass, and a production seam to skip `scheduleBackgroundWork()` — which
would also change what `MainActivityUiTest` boots into.

## Decision

Keep the fake-based suite, and add real execution alongside it, using the
one route the OS permits in each case:

- **`NotificationAlarmReceiver`** — an in-process explicit `sendBroadcast`.
  Its action prefix is this app's own rather than protected, and the
  receiver, though `exported="false"`, is reachable from its own uid. That
  is a genuine `ActivityThread` dispatch, so `goAsync()` works, and it costs
  milliseconds. One further test lets a real `AlarmManager` alarm deliver it,
  for the hop that only a device can answer.
- **`ExactAlarmPermissionReceiver`** — no simulated broadcast at all.
  `AlarmManagerService` watches `OP_SCHEDULE_EXACT_ALARM` and sends
  `ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED` itself when the op
  moves to allowed, and the shell holds `MANAGE_APP_OPS_MODES`. Flipping the
  op therefore makes the *OS* dispatch the *real* broadcast to the *real*
  exported receiver.
- **`BootReceiver`** — `am broadcast` from a root shell, arranged by
  `adb root` in `tools/ci/run-ui-tests.sh` (`am instrument` inherits adbd's
  uid). Best-effort: where adbd cannot be rooted those tests report
  themselves skipped via `assumeTrue`.
- **The three workers** — `TestListenableWorkerBuilder` with the real
  `SkywardWorkerFactory`, which needs no WorkManager initialisation and
  takes the same construction path WorkManager takes at runtime.
- **The denied exact-alarm state** — driven by `cmd appops`, with **API 34
  added to the CI matrix**. The permission stops being pre-granted at API 33
  for an app targeting 33+ (targetSdk is 36), so 33 or 34 would both produce
  the state; 31 and 32 would not, and below 31 there is nothing to deny. 34 is
  chosen as the more current, better-supported image, not because 33 lacks the
  behaviour. Restored with `default`, not `allow`, so the op goes back to being
  permission-derived.
- **The SAF round-trip** — `LocalActivityResultRegistryOwner` overridden
  with a recording `ActivityResultRegistry` that resolves each launch to a
  test-controlled `content://` Uri. Only the picker Activity is replaced;
  the contracts, the launcher callbacks, the `ContentResolver` IO and
  `SyncViewModel` are all production code.

Where a state is not producible, the test **skips with a stated reason**
rather than passing quietly. `SystemBroadcastReceiverTest` also asserts
unconditionally, via `queryBroadcastReceivers`, that every receiver is
declared for the actions it guards — so a skip can never amount to no
coverage at all.

## Two things the emulator taught us that no amount of reading would

Both surfaced on the very first matrix run and both are now baked into the
suites, because each looks like an obvious test to write and cannot be made to
work:

- **A test may not revoke `SCHEDULE_EXACT_ALARM`.** Moving the app-op to
  `ignore` makes the platform force-stop the app, which kills the
  instrumentation process and aborts every test after it — the first run got
  through 23 of 51 on `play`/API 34 before dying. So the standing-backup
  invariant is asserted structurally (the backup is `ENQUEUED` alongside the
  exact alarm) and the delivery half is covered by a test that *starts* from
  the denied state rather than moving into it. Granting is safe; revoking is
  not.
- **`RefreshWorker.doWork()` cannot be called from a test.**
  `SourceRunner.runDue` holds `runMutex`, and the app's own periodic
  `skyward-refresh` job — enqueued from `Application.onCreate`, which
  WorkManager starts immediately — is already inside it doing the first-ever
  `EclipseSource` path sampling. A test calling `doWork()` queues behind
  minutes of astronomy and dies on `runTest`'s one-minute deadline. Disabling
  the sources first does not help: the contending pass began before the test
  could disable anything. Worker construction through `SkywardWorkerFactory` is
  covered instead, which is where the real risk lives.

## Consequences

- The delivery path is covered end to end for the first time. A renamed
  worker, a receiver dropped from the manifest, a changed action prefix or a
  `PendingIntent` identity that stops matching `AlarmManager.cancel` now
  fails a test instead of silently costing users their reminders.
- §17.5's denied-permission scenario is produced by the OS on `play`/API 34,
  not by a fake. As a side effect, `theDefaultInstallStateOfThisFlavourMatchesItsManifest`
  turns D13's manifest split into an assertion about observable behaviour.
- CI runs four emulator test runs (2 API levels × 2 flavours) rather than
  two. The matrix entries run in parallel, so wall-clock is roughly
  unchanged and well inside the 60-minute per-entry budget.
- A "0 failures, N skipped" report is now a normal outcome, and reading it
  requires knowing why: on `foss` at API 33+ the exact-alarm tests skip
  because `USE_EXACT_ALARM` makes denial impossible — the permission working
  as designed. The skip messages say so.
- These tests register real OS alarms and real work items, which outlive the
  test method and the process. Every suite that schedules one cancels both
  halves and deletes the row in `@After`; `NotificationPoster` returning
  early when `getById` finds nothing makes an escapee harmless.
- One production change: `approximateWorkName` moves from `private` in
  `AndroidAlarmScheduler` to a top-level `internal` function in
  `AlarmIntents.kt`, beside `notificationPendingIntent`. That file already
  holds "the identities the OS knows this notification by", and the exact
  path's half was already `internal` for the same reason.
- `androidx.work:work-testing` is added, pinned to the same `workManager`
  version as `work-runtime-ktx`. It is `androidTestImplementation` only, so
  it is on neither the classpath `checkFlavourDependencyParity` compares nor
  the one `checkDependencyLicenses` reads (§16, §17.5b).
- §10.2 says the denied state is "always the initial state for the `play`
  flavour on API 31+". That is optimistic by two levels: 31 and 32 still grant
  `SCHEDULE_EXACT_ALARM` at install. It becomes true at 33 for an app targeting
  33+, which this one does. Recorded here rather than by editing the design
  doc.

## Alternatives considered

**Fire the receivers directly, as §17.5 says.** Rejected: `goAsync()`
returns null outside a system dispatch and the receivers then throw on an
un-handled coroutine scope, killing the test process. This is very likely
why the original suite settled for re-implementing the bodies — the reason
was sound, but the conclusion (do not run the receivers) was not the only
one available.

**A custom `AndroidJUnitRunner` plus a test `Application`, to make
`WorkManagerTestInitHelper` usable.** Rejected: it needs a production seam
to skip `scheduleBackgroundWork()`, it changes the `Application` every other
instrumented suite boots into, and it buys only a `TestDriver` for
constraint/delay manipulation that none of these tests need.

**Grant the tests root by making the whole emulator run as root, or drop
the boot tests.** Rejected both ways: `adb root` is scoped to adbd and
already the mechanism the emulator images support, and dropping the boot
tests would leave §17.5's "boot-receiver re-registration" exactly as
uncovered as before.

**Replace API 30 with API 34 rather than adding it.** Rejected: API 30 is
the only level in the matrix that exercises the `SDK_INT < S` branch of
`canScheduleExact()` and the pre-`POST_NOTIFICATIONS` notification path,
both of which are live for minSdk 26.

**Drive the SAF picker through UI automation.** Rejected: the picker is a
system Activity whose layout and behaviour differ by API level and system
image, it would need `uiautomator` as a new dependency, and it tests
Google's document picker rather than Skyward's use of it.
