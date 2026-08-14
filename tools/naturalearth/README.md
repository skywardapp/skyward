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
polygon rings — and puts that on the **desktop** target's resource path only.
`NaturalEarthMap` (core, desktopMain) reads it back. Nothing parses GeoJSON at
runtime, and the Android artifact never carries the data at all (§18 puts an
Android map tab in the v1.1 backlog).

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
`./gradlew :desktopApp:test` afterwards: `NaturalEarthMapTest` asserts the
decoded geometry still covers the whole globe and stays within valid
lon/lat bounds.
