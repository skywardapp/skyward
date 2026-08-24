# ADR 0021: The chart projections live in `:core`; only the drawing stays in the frontends

**Status:** Accepted

## Context

§4.1 is explicit that the frontends are not a shared layer:

> UI code is intentionally **not** shared between the two apps (D1 note:
> frontends may differ) — only `:core` is shared. If, during implementation,
> some small presentational helpers (formatting, colors for quality levels)
> want sharing, put them in `:core/format/` as pure functions.

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

What stayed: every `DrawScope` extension, `buildLandPath`/`landPath` (they
return a Compose `Path`), and the two thin functions that turn an
`IntArray` of ARGB pixels into the platform's own image type.

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
