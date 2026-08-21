# Build-time tools (dev only)

Not part of the app; scripts that run at build/dev time, not on-device.

- **Natural Earth GeoJSON → binary converter** (§14.1) — converts the
  1:50m land/coastline vectors into `core`'s compact binary map resource.
  Runs as part of the Gradle build, not at runtime. Lands with the desktop
  event map (M6).
- **Fixture fetchers** (§17.1–17.3b) — capture real HTTP responses (SWPC,
  EONET, JPL SBDB/Horizons) and GSFC eclipse canon rows into
  `core/src/commonTest/resources/fixtures/` for the golden tests. Lands with
  M1 (astronomy golden tests) and M4 (polled-source parser tests).

Both land with the milestones above, not M0.

## `ci/` — scripts the GitHub Actions workflows call

- **`run-ui-tests.sh`** — drives the Android instrumented tests (§17.5) on an
  already-booted emulator, once per flavour, with the screen recorded and
  logcat captured per flavour. A failing flavour does not skip the other one.
- **`record-screen.sh start|stop <label>`** — the recorder behind it.
  `screenrecord` caps one recording at 3 minutes, so it records back-to-back
  segments in the background and stitches them with ffmpeg on stop. Recording
  is best-effort: no device, no `screenrecord`, no ffmpeg → the tests still
  run and the job's result is unchanged.
- **`mock-notification-daemon.py`** — §17.5's "CI container with a mock
  notification daemon". Claims `org.freedesktop.Notifications` on the session
  bus and answers the methods a notifier calls, so `notify-send` succeeds on a
  headless runner with no desktop environment; every notification it accepts is
  appended as JSON to `$SKYWARD_MOCK_NOTIFICATION_LOG`, which is how
  `DesktopNotifierBackendTest` asserts the reminder text really crossed DBus.
  Pure `jeepney` (`pip install jeepney`) — the one Python DBus implementation
  needing no compiled bindings, where a real notification daemon would drag in
  X11 or Wayland. Run it inside a `dbus-run-session`:

  ```sh
  dbus-run-session -- bash -c \
    'python3 tools/ci/mock-notification-daemon.py & ./gradlew :desktopApp:test'
  ```

  Without it that one test skips itself rather than failing, per §17.5's own
  "or skip-if-no-dbus guard".
- **`check-reproducible-build.sh`** — §15.4/§17.5b's reproducibility check:
  builds `fossRelease` twice in a clean workspace and asserts the two APKs
  are reproducible — byte-for-byte identical, or (weaker, logged as such)
  identical once the signing block is stripped. Lands with M7. Runnable
  locally too; it just runs `./gradlew clean :androidApp:assembleFossRelease`
  twice and diffs the results, so budget for two full clean builds.

Both are runnable against a local emulator too — `adb devices` showing one
device is the only prerequisite:

```sh
FLAVOURS=foss tools/ci/run-ui-tests.sh   # video lands in build/ui-test-video/
```
