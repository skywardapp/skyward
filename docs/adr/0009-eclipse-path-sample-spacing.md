# 0009 — §17.2's 400 km sample-spacing bound is a typical case, not a ceiling

## Context

§17.2 requires, for the three named eclipses, that "consecutive samples
[be] < 400 km apart". §7.1.3 specifies how those samples are produced: the
coarse grid's hits are grouped into **2-minute buckets of local peak time**
and each bucket yields one refined centreline point.

Those two numbers are not compatible, and the incompatibility is physical
rather than an implementation shortfall. The umbra's centre does not move at
a constant speed along the track: where the shadow strikes obliquely, near
the sunrise and sunset ends of the path, its ground speed rises to several
km/s, so two minutes of shadow travel is far more than 400 km however
carefully those two minutes are sampled.

NASA/GSFC's own umbral-path tables settle it. They publish the central line
at exactly the 120-second interval §7.1.3 buckets to, and the checked-in
extract (`gsfc_central_paths_named_eclipses.csv`,
`tools/fetch-gsfc-eclipse-paths.py`) exceeds 400 km between consecutive rows
on all three eclipses:

| eclipse | published rows | worst consecutive gap | rows > 400 km apart |
|---|---|---|---|
| 2026-08-12 | 46 | 583 km | 1 |
| 2027-08-02 | 102 | 818 km | 4 |
| 2028-07-22 | 83 | 625 km | 2 |

So the canon this project validates against cannot satisfy §17.2 as written.
No sampler bucketing at 2 minutes can.

Skyward's own path is sparser still — median gaps of 279 / 316 / 371 km, but
worst gaps of 1326 / 1180 / 1079 km — because not every 2-minute bucket
produces a hit. Two causes, both in `EclipseSource.coarseScan`:

- Near the terminator the shadow crosses more ground per bucket than the
  2.5° coarse grid resolves in time, so occasional buckets are empty.
- The scanned latitude band is clamped to ±85° to stay away from the grid's
  polar singularity. The 2026-08-12 track reaches 89.1° N, so its polar cap
  is never scanned; that is the whole of that eclipse's single 1326 km gap,
  a 13-minute hole between 17:03 and 17:17 UT.

## Decision

Assert the spacing in two parts rather than dropping §17.2's number:

- **Median consecutive spacing < 400 km.** §17.2's bound, kept where it can
  hold, which is everywhere except the ends of the track.
- **No consecutive gap above 1500 km.** At the ~6.4 km/s ground speed
  measured at the 2027 sunrise end, a 2-minute bucket spans ~770 km, so this
  is "at most one dropped bucket, even at the fastest the shadow ever moves".

The substantive guarantee — that the path has no *holes*, only sparse
vertices — is carried by the separate assertion §17.2 also specifies and
which now passes at the spec's own tolerance: **every published centreline
point lies within 50 km of the sampled polyline** (46.8 / 10.4 / 21.1 km
worst case across the three eclipses). A missing stretch of track would show
up there as a chord cutting the curve, which is what "the path passes within
50 km of published centreline reference points" is really asking.

## Consequences

- §17.2's 400 km survives as a real assertion rather than being quietly
  dropped or loosened to a number chosen to pass.
- The 1500 km ceiling is a regression guard, not a target. It is loose enough
  that tightening the sampler would not trip it and tight enough that losing
  a second consecutive bucket would.
- The ±85° clamp remains as it is. Raising it is a §7.1.3 change with its own
  costs (grid degeneracy at the pole, more searches, a different emitted path
  for every polar eclipse and therefore a different determinism-guard
  baseline), so it is left for a deliberate decision rather than taken as a
  side effect of a test change. Until then, the 2026-08-12 path is drawn from
  its 85° N crossing onward, and the 50 km proximity assertion holds across
  the gap because the track is close to a great circle there.

## Alternatives considered

- **Assert §17.2's 400 km literally.** Fails against NASA's own published
  table, so it would be asserting something no correct implementation can
  satisfy.
- **Sample on distance instead of time.** Bucketing by along-track distance
  rather than 2-minute peak-time slots would bound spacing directly, but
  §7.1.3's bucket key *is* local peak time, and the natural key and dedup
  behaviour (§6.4, §10.4) plus the determinism guard (§17.6) are all built on
  that. A different bucketing is a design change, not a test fix.
- **Loosen to a single number that passes.** A bound of, say, 1400 km with no
  stated reason would pass today and mean nothing tomorrow; splitting median
  from worst case keeps the spec's number doing work.
