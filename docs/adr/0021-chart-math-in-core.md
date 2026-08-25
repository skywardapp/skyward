# ADR 0021: The chart projections live in `:core`; only the drawing stays in the frontends

**Status:** Accepted. Its premise moved on 2026-08-25: design doc 1.3 removed
§4.1's prohibition on sharing UI code (see the *Update* below). The decision
recorded here — the maths in `:core`, tested once — is unaffected and stands.

## Update (2026-08-25): §4.1 no longer forbids shared UI

Design doc 1.3 withdrew the rule this ADR quotes below, at the owner's
request, after the duplication it forced was measured on PR #127. UI code may
now be shared between the two apps; the module count and §15.3's Compose-free
`:core` are what remain.

Everything below is left as written, as the record of why the maths moved. Two
sentences in it are now historical rather than binding: "the drawing is still
not shared" (Decision) described the rule as it then stood, and the
*Alternatives considered* entry that rejects sharing the drawing rested partly
on §4.1. That leg is gone. The other legs of that rejection are not: the two
apps still build against different Compose distributions, and phone layouts
still differ from desktop ones (ADR 0022). Anything that does share drawing
code owes its own ADR — not because §4.1 demands one, which it no longer
does for any shared UI, but because such a change supersedes the decision
recorded here — and, per §4.1, a demonstration that it compiles under both
distributions. That compile demonstration is the doc's requirement and it
applies to every shared UI source, drawing or not.

## Context

§4.1, as it stood at design doc 1.2, was explicit that the frontends are not a
shared layer:

> UI code is intentionally **not** shared between the two apps (D1 note:
> frontends may differ) — only `:core` is shared. If, during implementation,
> some small presentational helpers (formatting, colors for quality levels)
> want sharing, put them in `:core/format/` as pure functions.

(The `(D1 note: frontends may differ)` parenthetical was a dangling citation:
`D1` appeared exactly twice in the design doc — its §2 row and this reference —
and its rationale column said nothing about frontends differing. Raised on
PR #127; this ADR quotes §4.1 as it was written and does not depend on the
parenthetical, only on the rule itself. Design doc 1.3 removed the whole
sentence, citation included, so both the quote above and this note are now
history.)

When M6 built §14's four visualizations, everything they needed went into
`:desktopApp` — including the parts that are not drawing at all. Five of
those files had already been written to avoid the Compose *runtime* on
purpose, so that they could be unit-tested; their own KDoc says so:

- `MapCamera` — "Deliberately free of any Compose *runtime* dependency (only
  the geometry value types) so the whole projection is unit-testable — the
  eclipse-path acceptance check in §18 is ultimately a statement about this
  arithmetic."
- `AuroraPolarPlot` — "Kept free of Compose runtime types (only
  geometry/graphics value classes) so the projection can be unit-tested
  directly."
- `SkyProjection`, `TimelineScale` and `SkySceneBuilder` are in the same
  position; `TimelineScale` and `SkySceneBuilder` import no Compose at all.

So the boundary between "maths" and "drawing" already existed and was
already load-bearing. It just ran through the middle of `:desktopApp`, where
only one frontend could reach it.

Bringing §14's views to Android (ADR 0022) made that placement cost
something real. Reimplementing a stereographic projection, an
equirectangular camera, a piecewise time axis and an azimuthal-equidistant
cap a second time would be P2's "frontends never reimplement domain logic"
in all but name — and §18's M6 acceptance criterion ("eclipse path for
2027-08-02 renders correctly vs reference map") is a claim about arithmetic
that would then have two implementations and one test.

## Decision

Move the projection, scene and scale maths into a new `:core` package,
`dev.fritze.skyward.core.chart`, and leave every `DrawScope` in the app that
owns it.

What moved: `ChartPoint`/`ChartSize` (new), `MapCamera`, `SkyProjection`,
`SkyScene`/`SkySceneBuilder`, `TimelineScale`, `OvationRamp`,
`AuroraPolarPlot`'s projection, `OvationRaster` (new — the pixel loops from
the two `BufferedImage` rasterizers), `NightWindow`/`nightAnchor`/
`nightWindow`, and `MapLayers`' geometry half (`MapLayer`,
`eclipsePathPolylines`, `eonetMarkers`, `travelRadiiKm`,
`travelCircleRadii`).

A second pass, prompted by SonarCloud's duplication gate on the Android
port, moved four more things the two frontends had been keeping their own
copies of. Each is a decision rather than a drawing, so each belongs here on
the same argument:

- `buildLandOutline` — the walk over 60 000 Natural Earth points, the
  world-coordinate mapping, and the antimeridian rule that stops Antarctica
  being closed with a streak across the map. Emitted through `moveTo`/
  `lineTo`/`close` callbacks, since the two apps build different `Path`
  types; each `buildLandPath` is now five lines.
- `mapHitTest` and `skyChartHitTest` — the "what did the user mean" rules:
  nearest within a threshold, and which layer outranks which. The pick
  *radii* stay per-frontend, because a fingertip is not a cursor.
- `forecastSlots` and `monthTicks` — §14.4's 3-hour buckets and §14.2's
  axis labels.
- `auroraVerdict` and its two sentences — §14.4 Row 3's §8.4-inverted
  threshold and the four-case `when` both dashboards printed.

What stayed: every `DrawScope` extension, the `Path` and `ImageBitmap`
containers those shared functions fill, and the per-frontend constants
(hit radii, canvas widths, label spacing) that exist precisely because a
phone and a desktop are not the same surface.

This is the §4.1 paragraph above applied at a larger size than "colors for
quality levels", not a departure from it: the drawing is still not shared,
and what moved is pure functions. §15.3's dependency list is unchanged —
`:core` gains no dependency from this.

### The geometry types

The one real obstacle was that these functions spoke
`androidx.compose.ui.geometry.Offset` and `Size`. §15.3 forbids Compose in
`commonMain`, so `commonMain` needed its own pair, and each frontend
converts at the point where it actually draws.

`ChartPoint`/`ChartSize` are that pair. They are deliberately minimal —
two floats, the vector operators `MapCamera` needs, `distanceTo` for
hit-testing, and `minDimension` for the circular charts.

Converting by hand at every call site would have buried the drawing in
noise, so each frontend carries a small adapter file of overloads taking the
Compose types (`desktopApp`'s `ui/common/ChartGeometry.kt`). Overload
resolution separates them from the `:core` members by parameter type, so the
rendering code reads as it did before the move.

## Consequences

- The projection tests moved to `core/src/commonTest/` and now run on
  **both** the JVM and Android, per §17's opening line. They previously ran
  on the JVM only. That is a coverage gain, not merely a relocation.
- Two pixel loops that no test could reach — they returned a
  `BufferedImage` — are now `IntArray` producers in `commonTest`'s reach.
- `MapLayersTest` split: the geometry half is
  `core/.../chart/MapLayersTest.kt`, and the two assertions needing the
  Natural Earth resource and a Compose `Path` are `:desktopApp`'s
  `MapBaseLayerTest`.
- A future third frontend gets the maths for free and owes only drawing.
- The cost is the adapter indirection, and that a reader following a
  `DrawScope` now crosses a module boundary to find the projection behind
  it. The KDoc at each moved declaration names the design-doc section it
  implements, which is how that reader gets back.

## Alternatives considered

- **Duplicate the drawing and the maths in `androidApp`.** Fastest to land
  and no refactor risk to a shipping desktop app, but it puts the §18 M6
  acceptance arithmetic in two places with one test, and every later fix has
  to be made twice. Rejected on P2 grounds.
- **Let `:core` `commonMain` depend on Compose's geometry artifact.** It
  would delete the adapters and `ChartPoint`/`ChartSize` entirely.
  `org.jetbrains.compose.ui:ui-geometry` is a real, small, GPL-compatible
  artifact — but §15.3's list is short and deliberate, and admitting the
  first Compose artifact to `commonMain` makes the second an argument about
  degree rather than about the rule. Rejected.
- **A fourth Gradle module for shared UI.** §4.1 forbids it in as many
  words ("Three Gradle modules only … Do **not** split `:core`").
- **Share the drawing too, via Compose Multiplatform in `androidApp`.** The
  two apps do not use the same Compose distribution (`androidApp` is on the
  AndroidX BOM, `desktopApp` on JetBrains Compose Multiplatform), and §4.1
  says UI code is intentionally not shared. The phone layouts differ from
  the desktop ones anyway (ADR 0022).
