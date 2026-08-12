# Skyward

Location-based reminders for natural & sky events — solar and lunar eclipses,
aurora, meteor showers, comets, and other rare sky events. Android (F-Droid
and Google Play) + Linux desktop, sharing one Kotlin Multiplatform core.
Local-only: no accounts, no hosted backend, no telemetry.

The full product and engineering specification lives in
[`docs/design-doc.md`](docs/design-doc.md) — read it before making changes;
this repository implements it. In particular:

- §2 is a **locked decision log**. Don't reopen those decisions; if an
  implementation detail conflicts with one, stop and surface it rather than
  silently deviating.
- §18 defines the **implementation milestones** (M0–M7) this repo builds up
  through, in order. Don't start a milestone before the previous one's
  acceptance criteria are met.

## Status

**M0 — Skeleton & CI.** Repository layout, Gradle multi-module setup
(`:core`, `:androidApp`, `:desktopApp`), version catalog, both Android
product flavours (`foss` / `play`) with their manifests, the vendored
Astronomy Engine compiling in `commonMain`, the SQLDelight schema, and CI.

## Project layout

```
skyward/
├── core/         Kotlin Multiplatform domain logic (model, astro, sources,
│                 visibility, rules, planner, persistence, sync, net)
├── androidApp/   Jetpack Compose Android app (foss + play flavours)
├── desktopApp/   Compose for Desktop Linux app
├── tools/        Build-time data converters (dev only)
├── fastlane/     F-Droid/Play store listing metadata
├── flatpak/      Flatpak manifest for desktop packaging
└── docs/         Design doc and ADRs for any deviations
```

Only three Gradle modules — see §4.1 of the design doc for why `:core` is
not split further.

## Building

Requires JDK 17+ and the Android SDK (`compileSdk 36`, `minSdk 26`).

```sh
./gradlew check                 # commonTest + lint across all modules
./gradlew :androidApp:assembleFossRelease
./gradlew :androidApp:assemblePlayRelease
./gradlew :desktopApp:run
```

`:core`'s `commonMain` depends only on kotlinx-{coroutines,serialization,datetime},
Ktor client core, and the SQLDelight runtime — no Compose, no AndroidX (§15.3).

## License

GPL-3.0-or-later (see [`LICENSE`](LICENSE); rationale in design doc D8).
Third-party components and data are enumerated in [`NOTICE`](NOTICE).
