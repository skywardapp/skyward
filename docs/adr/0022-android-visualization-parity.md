# ADR 0022: Android ships all four of §14's visualizations, not just the map

**Status:** Accepted

## Context

The design doc scopes drawn views to the desktop. §14 is titled "Desktop app
UI specification"; §1.4 casts Android as the "reminder machine in your
pocket" against the desktop's "planning & visualization workstation"; §13.1
marks the Android Map tab "v1.1 … hide behind flag"; and §18's v1.1 backlog
lists "Android Map tab" and, separately, "star catalog for sky chart" —
nothing else visual. D7 is worded as "Desktop visualizations".

That split was a scope decision made before M6 was built. Two things
learned in building it make the split cost more than it saves:

1. **The drawing code is already portable.** All four views draw with
   `Canvas`, `DrawScope`, `Path`, `Brush` and `withTransform`, whose package
   names (`androidx.compose.ui.graphics.*`) are identical under the AndroidX
   Compose BOM and Compose Multiplatform. The bodies compile on Android
   unchanged.
2. **The astronomy is already on Android.** Astronomy Engine is vendored
   into `core/commonMain` (§15.1, D9), so `SkySceneBuilder`'s
   `equator`→`horizon` work and the Kepler propagator behind §14.3's comet
   markers already run in the APK. Nothing had to be ported for them.

Three things genuinely were desktop-bound, and all three are addressed
elsewhere: two `BufferedImage` rasterizers (ADR 0021 — now `IntArray`
producers in `:core`), the Natural Earth reader and resource (ADR 0023), and
mouse hover/scroll gestures, which need touch equivalents.

Against that, the doc's own §13.3 already required a drawn view on Android
that was never built:

> path mini-map for eclipses [static canvas drawing of centralPath +
> location markers — no tile map needed]

So Android was already meant to draw, and did not.

## Decision

Ship §14.1's map, §14.2's timeline, §14.3's sky chart and §14.4's aurora
dashboard on Android, alongside §13.3's eclipse mini-map in EventDetail.

§1.4's "planning workstation" framing now describes screen size and layout,
not the feature list: the desktop keeps the nav rail, two-pane detail and
hover affordances a pointer makes worthwhile, and the phone gets the same
views laid out for a thumb.

**Navigation.** §13.1's bottom bar sketch has four items
(`[Upcoming] [Map*] [Rules] [Settings]`), and Material 3's `NavigationBar`
tops out around five, so the four views do not each get a tab. The planned
`Map` slot becomes a **Sky** tab holding all four behind a tab row. Item
count matches §13.1; what sits behind one of them is broader.

**§13.3's aurora hint is moot.** It specifies, for the aurora block, an
"open dashboard on desktop" hint. That hint was never built — it appears
nowhere in `androidApp` — and it is now the wrong advice anyway, since the
dashboard is on the device. Nothing is removed; the line simply stops being
something the Android app owes.

**§19 R10 still binds.** "Sky chart scope creep (star catalogs,
constellations) … v1 explicitly starless (§14.3); resist." This is a port of
the existing starless chart, not an occasion to revisit it. No catalog is
added, and `SkySceneBuilder`'s KDoc keeps saying so.

## Consequences

- Android carries ~384 KB more APK (ADR 0023), and four screens' worth of
  new UI and ViewModels to maintain against §14's spec.
- §18's M6 acceptance criterion — "eclipse path for 2027-08-02 renders
  correctly vs reference map" — now has a second place to be checked, and
  the arithmetic behind it is shared (ADR 0021), so the two cannot drift.
- D7's "Desktop visualizations" row is left as it was. It is in §2's locked
  log and records what the owner chose in 2026-08; this ADR is the record
  that the implementation went further, per CLAUDE.md's rule that the code
  deviates and the doc is not edited to match.
- Flavour parity (D13) is untouched: nothing here is flavour-specific, so
  §17.5b's three guards see no change.
- This is v1.1-shaped work landing while §18 is at M7. That ordering is a
  deliberate exception, not an oversight — see the PR discussion.

## Alternatives considered

- **Build only §13.3's eclipse mini-map.** Closes the actual v1 gap and
  nothing more. Cheapest, and defensible — but it leaves the four views
  behind a boundary that turned out to be nearly free to cross, and leaves
  §13.3's "open dashboard on desktop" as the answer for a phone user
  watching an aurora forecast.
- **Build the mini-map and the Map tab only** (§13.1/§18's actual v1.1
  scope). Coherent, but the map is the *most* expensive of the four — it is
  the one needing the Natural Earth resource and the APK growth — while the
  timeline, sky chart and aurora dashboard need no bundled data at all. The
  cheap three would have been deferred behind the expensive one.
- **Wait for v1 to ship.** The honest alternative, and the one this ADR is
  weakest against. See the ordering note above.

## Related

- [ADR 0021](0021-chart-math-in-core.md) — where the shared projections live.
- [ADR 0023](0023-natural-earth-on-android.md) — getting the map data into
  the APK, superseding ADR 0010.
- [ADR 0024](0024-charts-opt-out-of-dynamic-colour.md) — why these four
  screens are the app's only exception to §13's dynamic colour.
- [ADR 0025](0025-android-sky-tab-groups-the-drawn-views.md) — how they fit
  §13.1's four-item bottom bar.
- [ADR 0026](0026-eclipse-mini-map-pinch-zoom.md) — the fifth drawn view,
  §13.3's eclipse mini-map, gains the touch gestures the other four have.
