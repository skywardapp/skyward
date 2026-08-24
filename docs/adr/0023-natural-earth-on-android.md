# ADR 0023: The Natural Earth binary ships on Android too, wired into two source sets

**Status:** Accepted. Supersedes ADR 0010 (natural-earth binary in desktop resources).

## Context

ADR 0010 put `natural-earth.bin` on the desktop target's resource path
alone, for two stated reasons: the map was a desktop view in v1, and
"`commonMain/resources/` reaches the APK", so a `commonMain` placement would
add half a megabyte to an APK with no screen to draw it on.

It also wrote down what to do when that changed:

> When Android grows a map view (v1.1, §18), **two** things move to
> `commonMain` together, and neither is useful without the other: the
> `srcDir` wiring in `core/build.gradle.kts`, and the reader
> (`core/map/NaturalEarthMap.kt`) …

ADR 0022 grew that map view. This ADR is that clause being spent.

Following it turned up one thing ADR 0010 had wrong, and one it could not
have known.

**Wrong: `commonMain/resources/` does *not* reach the APK.** ADR 0010's
second reason assumed AGP merges `:core`'s common resources into every
Android variant. It does not — and the repository already knew, in a comment
ADR 0010 did not cross-reference. `ShowersResource.android.kt` says:

> verified empirically that AGP does not merge commonMain/resources into the
> androidTarget's packaged resources the way it does for the desktop jvm
> target (the file was simply absent from the built APK until copied here
> directly).

So `showers.json` is checked in twice, and `verifyShowerCatalogsMatch` exists
to keep the two copies identical. Moving `natural-earth.bin` to
`commonMain/resources/` would have produced the exact failure ADR 0010
warned about in reverse: a reader in `commonMain` looking for a resource
that never reached the APK, failing silently to an empty ring list and a
blank base map.

**Unknown at the time: the size.** ADR 0010 said "roughly half a megabyte".
The generated file is 491,050 bytes (1,422 rings, 60,669 points), and APK
entries are deflated: **~384 KB** of actual APK growth.

## Decision

Move the reader to `commonMain` as ADR 0010 directed, and wire the
`convertNaturalEarth` **task output** into both the `androidMain` and
`desktopMain` resource paths:

```kotlin
val androidMain by getting { resources.srcDir(convertNaturalEarth) }
val desktopMain by getting { resources.srcDir(convertNaturalEarth) }
```

Two source sets, one generated artifact. This is strictly better than
`showers.json`'s arrangement: because the file is generated rather than
checked in, there is no second copy in git, no opportunity to edit one and
not the other, and therefore **no parity guard to write** — nothing
corresponds to `verifyShowerCatalogsMatch` here, and nothing needs to.

The reader's three JVM-only pieces went as ADR 0010 said they must:

- `getResourceAsStream` is behind `expect`/`actual`
  (`loadNaturalEarthBytes`), copying `ShowersResource`'s seam rather than
  inventing one.
- `java.io.DataInputStream` became a big-endian decode over a `ByteArray`.
  The file is half a megabyte and is decoded exactly once, so reading it
  whole costs nothing worth engineering around.
- `System.err.println` on the missing-resource path is gone. The function
  returns an empty list, which is what the only caller could act on anyway.

## Consequences

- **The `fossRelease` APK grows by ~384 KB.** That is the real cost, against
  §15.4's interest in a small reproducible build. `convertNaturalEarth` is
  deterministic — it walks the GeoJSON in file order and writes fixed-width
  big-endian floats — so pointing a second source set at it does not
  threaten reproducibility.
- **`NaturalEarthMapTest` now exists.** `tools/naturalearth/README.md` had
  told anyone refreshing the data to re-run it since M6; the test was never
  written. It is now in `commonTest`, so it runs on Android too — which is
  also the check that the resource reaches the APK at all, since a missing
  file decodes to zero rings rather than throwing.
- **The decoder is testable for the first time.** The `DataInputStream`
  version needed a file on disk; a `ByteArray` decode can be handed a
  hand-built resource, so the big-endian contract with the Gradle task's
  `DataOutputStream` is now asserted rather than assumed.
- Two documents that asserted the opposite are corrected: `NOTICE` said "The
  Android artifact does not carry it", and `tools/naturalearth/README.md`
  said the data went to the desktop path "only". `NOTICE` mattered most —
  the Android About screen renders it verbatim as a packaged asset (§13.1
  calls that screen compliance-load-bearing), so it would have stated a
  falsehood about its own artifact. As AGENTS.md notes, `checkDependencyLicenses`
  never sees a bundled asset, so nothing automated would have caught it.
- §15.1's resource layout stays aspirational on this row, as ADR 0010 said —
  now for a different reason: not because the map is desktop-only, but
  because AGP will not serve `commonMain/resources` to an APK.

## Alternatives considered

- **`commonMain/resources/` as §15.1 shows.** What ADR 0010 named as the
  correct end state. It is not: the file would not reach the APK. Rejected
  on the empirical finding above.
- **Check a second copy into `androidMain/resources/`, as `showers.json`
  does.** Consistent with existing practice, and it would work — at the cost
  of half a megabyte of generated binary in git, a second thing to
  regenerate on every refresh, and a `verifyNaturalEarthMatch` guard to stop
  the two drifting. All of that is avoidable for a generated file.
- **Ship a coarser vintage (1:110m) on Android to save APK size.** Roughly a
  fifth of the size, and defensible on a phone screen. Rejected for now: it
  makes the two frontends draw visibly different coastlines from two
  bundled datasets, and §14.1 names the 1:50m layer. Worth revisiting if APK
  size ever becomes the binding constraint.
