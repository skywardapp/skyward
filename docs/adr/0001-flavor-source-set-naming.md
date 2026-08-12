# ADR 0001: Flavor source-set directory names are `src/foss/` and `src/play/`, not `src/fossMain/`/`src/playMain/`

**Status:** Accepted (implementation correction, not a reopening of D1–D13)

## Context

Design doc §15.1 and §10.2 specify the flavour-specific manifest files at
`androidApp/src/fossMain/AndroidManifest.xml` and
`androidApp/src/playMain/AndroidManifest.xml`.

That path does not match the Android Gradle Plugin's actual source-set
convention. For a single flavor dimension, AGP creates one source set per
flavor named after the flavor itself — `src/foss/`, `src/play/` — plus the
usual `src/main/`, `src/debug/`, `src/release/`. The `<flavor><BuildType>`
naming (e.g. `fossDebug`) only applies to the *combined variant* source set,
which is for code/resources that should apply to one specific flavor+build-type
pairing, not "this flavor, every build type." A manifest meant to apply to a
flavor regardless of build type belongs in `src/<flavorName>/`.

Verified empirically during M0: with the file at `src/fossMain/AndroidManifest.xml`,
`processFossDebugMainManifest --info` merges only `src/main/AndroidManifest.xml`
into the variant — `src/fossMain/` is silently ignored, so neither
`USE_EXACT_ALARM` nor `SCHEDULE_EXACT_ALARM` ever reached the merged manifest
of either flavour. Renaming the directories to `src/foss/` and `src/play/`
fixed it immediately (confirmed via `--info` log: `Merging flavors and build
manifest .../src/foss/AndroidManifest.xml`).

## Decision

Use `androidApp/src/foss/AndroidManifest.xml` and
`androidApp/src/play/AndroidManifest.xml`. Everywhere else in this repo
(comments, the manifest-parity CI check) refers to these paths, not the
doc's `fossMain`/`playMain` spelling.

## Why this isn't a D13 deviation

D13 says "product flavours exist only for the exact-alarm permission
difference … they carry no billing, entitlement, or feature-gating code."
That invariant is unaffected — this ADR only corrects which directory name
makes the manifests actually take effect. Silently leaving the doc's spelling
would have shipped an app where neither flavour ever requests an exact-alarm
permission at all, which is a functional bug, not a faithful implementation
of D13.
