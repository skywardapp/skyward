# Build-time tools (dev only)

Not part of the app; scripts that run at build/dev time, not on-device.

- **Natural Earth GeoJSON → binary converter** (§14.1) — converts the
  1:50m land/coastline vectors into `core`'s compact binary map resource.
  Runs as part of the Gradle build, not at runtime. Lands with the desktop
  event map (M6).
- **Fixture fetchers** (§17.1–17.3b) — capture real HTTP responses (SWPC,
  EONET, JPL SBDB/Horizons) into `core/src/commonTest/resources/fixtures/`
  for the golden tests. See [`fixtures/README.md`](fixtures/README.md). The
  GSFC eclipse canon rows are the exception: they are transcribed from
  published HTML canon tables rather than fetched, and
  `core/src/commonTest/resources/fixtures/README.md` records where each came
  from.

## `ci/` — scripts the GitHub Actions workflows call

- **`run-ui-tests.sh`** — drives the Android instrumented tests (§17.5) on an
  already-booted emulator, once per flavour, with the screen recorded and
  logcat captured per flavour. A failing flavour does not skip the other one.
- **`record-screen.sh start|stop <label>`** — the recorder behind it.
  `screenrecord` caps one recording at 3 minutes, so it records back-to-back
  segments in the background and stitches them with ffmpeg on stop. Recording
  is best-effort: no device, no `screenrecord`, no ffmpeg → the tests still
  run and the job's result is unchanged.
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
