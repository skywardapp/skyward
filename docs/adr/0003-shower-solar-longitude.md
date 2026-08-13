# ADR 0003: Meteor shower `start`/`finish`/`peak` are solar-longitude degrees, not `MM.DD` dates

**Status:** Accepted (implementation correction anticipated by the design doc's own R2, not a reopening of D1–D13)

## Context

Design doc §7.2.1 describes the bundled Stellarium catalog's activity
entries as: *"start/finish/peak as MM.DD"* (calendar dates). §7.2.2 step 3
treats refining these to an exact instant as an *optional* enhancement,
with a documented fallback: "otherwise use 00:00 UTC of the peak date and
treat precision as ±12h."

The actual bundled file (`plugins/MeteorShowers/resources/MeteorShowers.json`
in the Stellarium repo, fetched 2026-08-13) stores these fields as **solar
longitude in degrees** (λ☉, the Sun's apparent geocentric ecliptic
longitude of date — 0° at the March equinox), not calendar dates. Confirmed
directly against the plugin's own parser
(`plugins/MeteorShowers/src/MeteorShower.cpp`):

```cpp
d.start = activityMap.value("start").toDouble();   // solar longitude at start
d.finish = activityMap.value("finish").toDouble();  // solar longitude at finish
d.peak = activityMap.value("peak").toDouble();      // solar longitude at ZHRmax
```

This is exactly the risk §19's R2 names and tells the implementer to check
for: *"Stellarium showers.json field semantics (drift units, per-year
entries) drift from this spec... Verify against Stellarium plugin source at
implementation."* There's no `MM.DD` fallback available — the "optional
enhancement" §7.2.2 describes turns out to be the *only* data the catalog
actually provides.

A second, related field-semantics finding: the plugin's `update()` applies
radiant drift as `driftAlpha/Delta * (currentSolarLongitude - activity.peak)`
— i.e. per **degree of solar longitude elapsed**, not per calendar day as
§7.2.2 step 4 assumes ("Stellarium drift is per-day"). One consequence
simplifies this app's payload: evaluated exactly at a shower's own peak
instant, that factor is `currentSolarLongitude - activity.peak = 0` by
construction, so the "radiant at peak" `MeteorShowerPayload` field is the
catalog's `radiantAlpha`/`radiantDelta` value unmodified — no drift
correction is needed for *this* field. Drift still matters for a radiant
position on any *other* night (the sky chart, M6), which isn't built yet.

A third finding, the one with real functional consequences: because
`start`/`finish`/`peak` are all solar-longitude values in the same rolling
0–360° space, a shower whose activity window straddles the calendar year
boundary can have a `start` value that is numerically *less than* its
`peak` (e.g. the Quadrantids: generic `start=275`, `peak=283.15`,
`finish=292` — all within one continuous ~17° band). A naive "solve for
this longitude within calendar year Y, searching forward from Jan 1"
approach gets the peak right but wraps `start` a full year late, since
275° also occurs the *following* December. Confirmed by running exactly
this naive approach against the Quadrantids and getting `start` in
December of the peak's own year instead of the year before.

## Decision

- `core/astro/SolarLongitude.kt` finds instants via the vendored Astronomy
  Engine's own `sunPosition(t).elon` (a handful of fixed-rate correction
  iterations — solar longitude is monotonic and near-linear across a
  year), not a ported curve-fit. This is more precise than Stellarium's own
  approximation and keeps every date in this codebase computed the same way.
- Two entry points, not one: `instantForSolarLongitudeInYear(target, year)`
  finds the first crossing on/after Jan 1 of `year` — used only for the
  *peak*, which is what "which calendar year's apparition is this" means.
  `instantForSolarLongitudeNear(target, anchor)` finds the crossing closest
  to a given instant — used for `start`/`finish`, anchored to the peak
  already found, which sidesteps the year-boundary ambiguity entirely
  rather than trying to special-case "showers that cross New Year's."
- `MeteorShowerSource` uses the catalog's `radiantAlpha`/`radiantDelta`
  directly for the peak-time radiant, per the zero-drift-at-peak finding
  above — no separate drift-application step for this payload.

`core/src/commonTest/.../MeteorShowerSourceTest.kt`'s
`quadrantidsStartFallsInThePreviousDecember` is the regression test for the
year-wrap case; `solarLongitudeRootFindingRoundTrips`,
`perseidsPeakFallsInMidAugust`, and `geminidsHaveAFixedNonVariableZhr` cover
the rest.

## Why this isn't a D-decision reopening

D9's rationale for computing eclipses on-device rather than fetching them —
"one code path... arbitrary date range" — applies with equal force here;
this ADR is in that same spirit, just for a different phenomenon's data
source. None of D1–D13 specify the shower catalog's field format; §7.2 is
an implementation specification the doc itself flagged as likely to need
on-the-ground verification (R2), and this is that verification.
