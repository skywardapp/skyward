# Natural Earth map vectors (§14.1)

`ne_50m_land.geojson.gz` is Natural Earth's 1:50m **land** polygon layer,
gzipped as committed (1.6 MB → 0.5 MB). Public domain; see the NOTICE entry
and the courtesy credit "Made with Natural Earth" rendered in the About
section.

It is vendored rather than downloaded at build time on purpose: Flathub and
F-Droid builds are network-isolated (§15.4, §15.5), so anything the build
needs has to already be in the repository.

## What consumes it

`:core`'s `convertNaturalEarth` Gradle task (see `core/build.gradle.kts`)
converts it into `natural-earth.bin` — a flat big-endian float array of
polygon rings — and puts that on the **android and desktop** targets' resource
paths. `NaturalEarthMap` (core, commonMain) reads it back. Nothing parses
GeoJSON at runtime.

Both frontends draw the map (ADR 0022), so both need the data. The one
generated file is wired into two source sets rather than copied into them,
because AGP does not merge `commonMain` resources into the APK — ADR 0023 has
the detail, and `ShowersResource.android.kt` documents the same finding for a
file that *is* checked in twice.

Coastlines are not a separate layer here: the land polygons' own outlines are
the coastline, so drawing them stroked and filled gives §14.1's "land +
coastline" from one dataset.

## Refreshing it

```sh
curl -L -o ne_50m_land.geojson \
  https://raw.githubusercontent.com/nvkelso/natural-earth-vector/master/geojson/ne_50m_land.geojson
gzip -9 -c ne_50m_land.geojson > ne_50m_land.geojson.gz
rm ne_50m_land.geojson
```

The converter reads whatever `Polygon`/`MultiPolygon` features the file
contains, so a newer vintage needs no code change — but do re-run
`NaturalEarthMapTest` afterward, on **both** targets:

```sh
./gradlew :core:desktopTest :core:testDebugUnitTest
```

It asserts the decoded geometry still covers the whole globe and stays within
valid lon/lat bounds. Run both because they exercise different packaging: the
desktop task reads the KMP source set, the Android one reads AGP's, and only
the second can catch the resource failing to reach the APK (ADR 0023).
