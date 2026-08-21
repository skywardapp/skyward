# ADR 0010: The Natural Earth binary is generated into the desktop target's resources, not `commonMain`

**Status:** Accepted

## Context

§14.1 specifies the map base layer as *"Natural Earth 1:50m land + coastline
polygons (public domain), converted at build time from GeoJSON to a compact
binary float-array resource"*, and §15.1's resource layout shows bundled
`:core` data living in `core/src/commonMain/resources/` — which is where
`showers.json` sits, and where a reader would expect `natural-earth.bin` to
sit too.

It does not. `core/build.gradle.kts`'s `convertNaturalEarth` task wires its
output into the **desktop** target's resources only:

```kotlin
val desktopMain by getting {
    resources.srcDir(convertNaturalEarth)
    ...
}
```

Two facts drive that:

- **The map is desktop-only in v1.** §14.1's map view is a desktop screen;
  §18 puts Android's Map tab in the v1.1 backlog explicitly. No code in
  `androidApp/` reads the file, and none can until that milestone.
- **`commonMain/resources/` reaches the APK.** Android's resource merging
  pulls `:core`'s common resources into every variant, so a `commonMain`
  placement would add roughly half a megabyte of coastline vectors to an APK
  that has no screen to draw them on — against §15.4's interest in a small,
  reproducible `fossRelease`, and for no user-visible benefit.

The reasoning was recorded in a comment at the task (`core/build.gradle.kts`),
but not as an ADR, so §15.1's layout and the build disagreed with no findable
record of which was intended.

## Decision

Keep generating `natural-earth.bin` into `desktopMain`'s resources, and record
the deviation here.

When Android grows a map view (v1.1, §18), **two** things move to
`commonMain` together, and neither is useful without the other: the
`srcDir` wiring in `core/build.gradle.kts`, and the reader
(`core/map/NaturalEarthMap.kt`), which is a `desktopMain` file for the same
reason the resource is. Moving only the resource would package half a
megabyte into the APK with no code able to read it; moving only the reader
would leave it looking for a resource that is not there.

Both are platform-neutral already — the binary format, the conversion task
and the parser use nothing JVM-specific beyond `getResourceAsStream`, which
is the one call the move has to replace with a `expect`/`actual` resource
read (§15.3 keeps `commonMain` off platform APIs). That is the whole of the
work; nothing about the format or the map rendering follows.

## Consequences

- The APK does not carry map vectors it cannot render; the desktop
  distributable is unchanged.
- The reader (`core/map/NaturalEarthMap.kt`) is a `desktopMain` file for the
  same reason, so resource and parser move together when the time comes.
- §15.1's layout is, on this row, aspirational rather than descriptive. This
  ADR is the pointer that says so.

## Alternatives considered

- **Generate into `commonMain/resources/` as §15.1 shows.** Correct the day
  Android draws a map; today it is half a megabyte of dead weight in every
  APK.
- **Generate into both, and exclude from the Android variant.** AGP resource
  exclusions are per-variant packaging rules applied after merging; expressing
  "this one `:core` resource, never on Android" that way is more machinery
  than the `srcDir` line it replaces, and it fails silently if the pattern
  stops matching.
- **Fetch the GeoJSON at runtime instead of bundling.** Rejected by D3's
  local-only posture and by §14.1's "convert at build time, not at runtime".
