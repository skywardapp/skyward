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

Both are runnable against a local emulator too — `adb devices` showing one
device is the only prerequisite:

```sh
FLAVOURS=foss tools/ci/run-ui-tests.sh   # video lands in build/ui-test-video/
```
