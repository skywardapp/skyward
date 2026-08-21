# ADR 0014: the Kepler propagator oracle's tolerances are tighter than §17.3b's floor, not looser

**Status:** Accepted (closes the ADR gap issue #74 identified)

## Context

Issue #74 found `KeplerHorizonsFixtureTest`'s predecessor about 50x looser
than §17.3b specifies (`max(0.05 au, 5%·r)` against a spec floor of
`±0.001 au`), with no comparison of `delta`, RA/Dec or T-mag at all. Per
CLAUDE.md, a deviation from the design doc needs an ADR; none of 0001–0013
covered it, which is exactly what the issue flagged.

The old test's looseness had a real cause, not just an unambitious bound:
it propagated *today's* osculating elements back across several
apparitions, which accumulates every perturbation the two-body model in
`Kepler.kt` omits — Horizons' own ephemerides are fully perturbed (n-body),
so that comparison was measuring staleness of the elements as much as
correctness of the propagator.

## Decision

Fetch each comet's osculating elements **at its own perihelion passage**
(`tools/fixtures/fetch-horizons.py`, `ELEMENTS` endpoint) and compare the
propagator against daily Horizons `VECTORS`/`OBSERVER` output over that
perihelion ±182 days — the window §17.3b asks for, but anchored so the
comparison measures the solver, not four to eight years of un-modelled
perturbation on top of it.

Measured against that anchor, two-body propagation beats §17.3b's floor
(`r` ±0.001 au, `delta` ±0.002 au, RA/Dec ±0.05°, magnitude ±0.1 mag) on
every axis across all four named comets and the full year each — by
roughly 5.6x on `r` at its worst (Borisov), 11x on `delta`, 31x on
direction and 40x on magnitude (`position` has no floor of its own in
§17.3b; measured against the same 0.001 au as `r`, its worst margin is
about 4.2x, also on Borisov):

```text
comet     r          position   delta      direction   magnitude
Encke     1.4e-4 au  1.6e-4 au  1.5e-4 au  0.0011 deg  0.0011 mag
NEOWISE   2.8e-5 au  6.4e-5 au  3.1e-5 au  0.0010 deg  0.0025 mag
Borisov   1.8e-4 au  2.4e-4 au  1.8e-4 au  0.0016 deg  0.0011 mag
96P       1.8e-5 au  7.7e-5 au  1.9e-5 au  0.0016 deg  0.0011 mag
```

`KeplerHorizonsFixtureTest`'s ceilings — `R_TOLERANCE_AU =
DELTA_TOLERANCE_AU = POSITION_TOLERANCE_AU = 0.0005`,
`DIRECTION_TOLERANCE_DEG = 0.005`, `MAGNITUDE_TOLERANCE = 0.01` — sit at
roughly 2x to 4x the worst residual actually observed per metric (2.1x on
`position`, 2.8x on `r` and `delta`, 3.1x on direction, 4x on magnitude),
well inside §17.3b's floor on every axis, so §17.3b is satisfied *a
fortiori*. A margin over the worst observed value, not the value itself,
so the fixture can be refreshed without the test flaking on ordinary
noise between captures.

## Consequences

- This bounds the propagator over half a year either side of an
  element epoch, using elements captured at that epoch. It does **not**
  bound the app's end-to-end comet accuracy in the field, which depends on
  how stale the live SBDB elements are and how far §7.4.3's scan reaches
  past them — §7.4.4's honesty requirements exist for that gap, and the
  v1.1 backlog item "non-gravitational terms in comet propagation" is
  where it would narrow.
- A future fixture refresh that pushes the measured worst residual close
  to or above these ceilings is a signal to look, not to reflexively widen
  the constant: either the new capture is noisier (recheck the margin), or
  the propagator regressed (open an issue, don't just loosen the test).
- Adding a fifth comet to the oracle needs its own row measured and
  added to the table above — the tolerances are not guaranteed to hold for
  an eccentricity/perihelion regime none of the current four comets
  exercise.

## Alternatives considered

- **Assert exactly at §17.3b's stated floor.** Legal, but well over an
  order of magnitude looser than what the propagator demonstrably
  achieves on most axes — a regression (a sign error in the RA/Dec
  rotation, a units mistake reintroducing itself) would have to get an
  order of magnitude worse than "wrong" before this test caught it. A
  tolerance this test exists to be a regression net against needs to be
  near the real error, not at the spec's stated ceiling.
- **Keep the old approach** (propagate current elements across several
  apparitions, tolerance `max(0.05 au, 5%·r)`). This is the bug issue #74
  reported: it bounds two-body drift over years of un-modelled
  perturbation, not the solver's per-call accuracy, and the resulting
  tolerance is too loose to catch the failures §7.4.1 warns about (a
  missed JD/TDB conversion "shows up as a wholesale ephemeris offset
  rather than an error").
- **Assert at exactly the worst observed residual (1x).** Rejected as too
  brittle: a routine fixture refresh (§17's "every number moves" — new
  Horizons capture, revised osculating fit) would flake the test on noise
  rather than signal, defeating the point of pinning a margin.
