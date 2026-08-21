# AGENTS.md — working on Skyward

Instructions for coding agents (Claude Code and others) and for humans who
want the same context. Read this first, then read the parts of
[`docs/design-doc.md`](docs/design-doc.md) that cover what you're changing.

## The one rule that matters most

**`docs/design-doc.md` is the specification; this repository is its
implementation.** It is not background reading — it is the source of truth for
domain behaviour, and almost every question you have ("what should the aurora
visibility model do at Kp 6?", "which permissions may the two flavours
differ on?") is already answered there.

Three parts of it are binding:

- **§2 — decision log (locked).** Do not reopen D1–D13. If an implementation
  detail genuinely conflicts with one, stop and surface it in the PR rather
  than silently deviating.
- **§18 — milestones M0–M7.** Built in order; don't start a milestone before
  the previous one's acceptance criteria are green. See the README for where
  the project currently stands.
- **§17 — testing strategy.** It names the specific tests each feature owes.

When the code must deviate from the doc, write an ADR in `docs/adr/`
(`NNNN-kebab-title.md`, following the existing Context / Decision /
Consequences / Alternatives considered shape) and reference it from a comment
at the point of deviation. Don't edit the design doc to match the code.

## What Skyward is

Location-based reminders for sky events (eclipses, aurora, meteor showers,
comets, supermoons, conjunctions). Android + Linux desktop over a shared
Kotlin Multiplatform core. **Local-only: no accounts, no backend, no
telemetry, no push service** — the only network traffic is direct HTTPS from
the device to NOAA SWPC, NASA EONET and NASA/JPL. GPL-3.0-or-later.

## Layout

```text
core/         Kotlin Multiplatform domain logic — model, astro, sources,
              visibility, rules, planner, persistence, sync, net, format
androidApp/   Jetpack Compose app; foss + play flavours
desktopApp/   Compose for Desktop (Linux)
tools/        Dev-only build scripts and CI helpers
docs/         design-doc.md + ADRs
fastlane/     Store listing metadata (F-Droid reads this directly)
flatpak/      Flatpak manifest for desktop packaging
```

Three Gradle modules, and only three (§4.1). Do **not** split `:core`;
package-level separation inside it is the design. Do not add a module.

## Commands

```sh
./gradlew check                                  # all modules: tests, lint, parity/licence checks
./gradlew :core:desktopTest                      # core domain tests on the JVM (fastest useful loop)
./gradlew :desktopApp:test
./gradlew :desktopApp:run                        # run the desktop app
./gradlew :desktopApp:createReleaseDistributable # self-contained jlinked tree
./gradlew :androidApp:assembleFossDebug :androidApp:assemblePlayDebug
tools/ci/check-reproducible-build.sh             # §15.4 reproducibility (two clean builds, slow)
```

Requires JDK 17+. The Android tasks additionally need the Android SDK
(`compileSdk 36`, `build-tools 36.0.0`); in a sandbox without one, `:core`'s
JVM tests and the desktop tasks still work, but `./gradlew check` will not
get through the Android modules — say so rather than reporting a green run
you didn't get.

`./gradlew check` is not just tests. It also runs `verifyShowerCatalogsMatch`,
`checkFlavourManifestParity`, `checkFlavourDependencyParity` and
`checkDependencyLicenses` — see "Invariants" below for what each protects.

`debug-matches` is a headless CLI path through the whole domain pipeline
(§18/M2's acceptance command). CI runs it against the *packaged* binary,
because that is the startup path a packaging mistake actually breaks (ADR
0007); locally it's:

```sh
./gradlew :desktopApp:run --args=debug-matches
```

## Invariants that are easy to break

- **`:core` `commonMain` stays pure** (§15.3): kotlinx-{coroutines,
  serialization, datetime}, Ktor client core, SQLDelight runtime only. No
  Compose, no AndroidX. Platform bits go in `androidMain`/`desktopMain` behind
  `expect`/`actual`.
- **The two Android flavours differ only in the exact-alarm permission**
  (D13, §15.1). `src/fossMain/` and `src/playMain/` contain one
  `AndroidManifest.xml` each and **no `.kt` files**. Adding flavour-specific
  Kotlin, a `BuildConfig` field, or a dependency to one flavour fails CI —
  correctly.
- **No monetization machinery** (P6/D13): no billing, entitlements, licence
  checks, ads or donation links. Equally, no dependency or data source whose
  licence forbids commercial use (this is why comets come from JPL, not COBS
  — D12). `checkDependencyLicenses` enforces the §16 allowlist/denylist.
- **The `fossRelease` build must stay reproducible** (§15.4): it is what lets
  F-Droid republish the developer-signed APK, which is what lets users move
  between stores. No build timestamps, no nondeterministic codegen, versions
  pinned exactly in `gradle/libs.versions.toml`.
- **Never bump a version by hand.** `versionCode`/`versionName` are derived
  from the latest `vMAJOR.MINOR.PATCH` git tag in the root `build.gradle.kts`;
  a release is cut by pushing a tag. There is no version field to edit.
- **Dependency versions live only in `gradle/libs.versions.toml`**, pinned per
  §15.2 and updated deliberately, in lockstep.
- **`showers.json` exists twice** (`core/src/commonMain/resources/` and
  `core/src/androidMain/resources/`) because AGP needs its own copy. Update
  both together; `verifyShowerCatalogsMatch` fails the build if they diverge.
- **Never commit signing material.** `keystore.properties`, `*.jks`,
  `*.keystore` are gitignored; the key reaches a build via that file or the
  `SKYWARD_RELEASE_*` env vars. See `keystore.properties.example` and
  [`RELEASE.md`](RELEASE.md).
- **The planner is a pure function** (§4.2). Platform code only translates
  `PlannedNotification` rows into OS alarms/notifications. Keep domain logic
  out of the UI layers — frontends never reimplement it (P2).

## Code conventions

- Kotlin official style (`kotlin.code.style=official`), 4-space indent,
  explicit imports (no wildcards), trailing commas in multi-line parameter
  lists.
- **Comments cite the spec.** The prevailing style is a KDoc or block comment
  naming the design-doc section a piece of code implements (`/** §9.2 step 3:
  … */`) and, where a choice is non-obvious, why the alternative was rejected.
  Match that density: explain the reasoning a reader can't recover from the
  code, not what the code plainly says.
- All core APIs are `suspend` or return a `Flow` (§4.3).
- Prose in comments and docs wraps at roughly 80 columns.

## Testing

- Domain tests live in `:core` `commonTest` and run on both JVM and Android.
  The one deliberate exception is the golden tests that read fixture *files*:
  they live in `desktopTest` and are JVM-only (ADR 0009).
- Astronomy and parser tests run against checked-in fixtures (golden GSFC
  eclipse rows, captured SWPC/EONET/JPL responses, JPL Horizons ephemerides) —
  §17.1–17.3b. Regenerate fixtures with the `tools/fixtures/` fetchers, never
  by hand; refreshing one is a review of the diff, not a rubber stamp. Inputs
  written to provoke a specific failure (malformed rows, swapped columns) are
  not fixtures and stay inline in `commonTest`, named `*_SAMPLE`.
- §17.6's determinism guard runs the whole pipeline twice and asserts
  identical planned notifications; the natural-key/dedup design (§6.4, §10.4)
  depends on it, so treat a failure there as a design bug, not a flaky test.
- Android instrumented tests (§17.5) live in `androidApp/src/androidTest/` and
  need an emulator, so they run from `.github/workflows/android-ui-tests.yml`
  rather than `ci.yml` — `tools/ci/run-ui-tests.sh` drives them once per
  flavour and records the screen. The video is as much the point as the
  pass/fail: a stolen focus or an unsettled frame is near-undiagnosable from a
  stack trace with no device left to look at.
- New behaviour ships with the tests §17 names for it.

## Git and PRs

- Work on a feature branch; changes reach `main` through a pull request.
- Commit subjects are imperative and describe the behaviour change, not the
  files touched: *"Refuse to publish a GitHub release without the signing
  key"*, *"Anchor the sky chart's night to local noon"*. Bodies explain what
  was wrong and why this is the fix, wrapped at ~72 columns. Reference
  design-doc sections and issue numbers where they apply.
- CI (`.github/workflows/ci.yml`) runs `check`, assembles both flavours in
  debug and release, compiles and packages the desktop app, smoke-tests the
  packaged binary, and verifies `fossRelease` reproducibility in a separate
  job. Automated reviewers comment on PRs; address their findings in
  follow-up commits.
- Update `README.md`'s status section when a milestone's state changes, and
  `RELEASE.md` when the release process does.
