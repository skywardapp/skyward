# ADR 0009: `Schedule.OnHorizonChange` sources re-run on a daily timer

**Status:** Accepted (refines §6.1's `OnHorizonChange` and §6.2's scheduling)

## Context

§6.1 gives COMPUTED sources `Schedule.OnHorizonChange`, described as
"recompute when horizon/locations/settings change", and §6.2 has
`SourceRunner` run "each enabled source whose next-run time ... ≤ now, plus
every source whose id is in `force`". Read literally, an `OnHorizonChange`
source has no next-run time at all: it is event-driven, so `SourceRunner`
stored no `next_run_at` for one and it never became due again on its own.

The horizon, though, is not an event. It is `now .. now + horizonYears`
(§7.1.2), so it changes continuously — its far edge sweeps forward one day
per day, revealing new eclipses, showers and conjunctions. Something has to
notice, and with no due-ness of their own the only mechanism left was the
frontends forcing every COMPUTED source on every periodic pass: Android's
`RefreshWorker` and the desktop `SourceRefreshLoop` both did, every 15
minutes.

That was expensive in a way the comment claiming it was "cheap (local
astronomy, no network)" hid. `EclipseSource.refresh` re-runs §7.1.3's path
sampling for every total/annular eclipse in the horizon, whose *per-eclipse*
budget is the <60 s desktop / <3 min Android of §7.1.3 itself; a 3-year
horizon holds six to ten of them. On a mid-range phone a pass could run for
tens of minutes against WorkManager's ~10-minute ceiling for a non-expedited
worker — and because `DefaultSources` lists the COMPUTED sources first, the
worker was killed *before* the POLLED ones (aurora, comets, EONET) ran at
all. The periodic path never polled. Desktop merely burned the CPU.

It also defeated §11's `visibility_cache`: `data_version` is keyed on
`occurrence.fetched_at`, which every recompute rewrote, so cached verdicts
for computed phenomena were discarded every 15 minutes and the cache never
hit.

## Decision

`Schedule.OnHorizonChange` sources get a `next_run_at` like any other:
`now + 1 day`. `force` reverts to what §6.2 says it is for — reacting to a
change *now* (onboarding finishing, a settings edit, pull-to-refresh) — and
neither periodic driver forces anything.

Two supporting changes make that safe:

- A source with **no** `next_run_at` is due immediately, rather than never.
  That is what bootstraps a fresh install now that the periodic drivers no
  longer force anything; it also fixes POLLED sources, which nothing on the
  periodic path had ever made due.
- `SourceRunner` leaves an occurrence's row untouched when a re-run returns
  it byte-identical, so `fetched_at` — and the visibility cache keyed on it
  — stays put across a recompute that found nothing new.

`EclipseSource` additionally caches its sampled paths in `source_state`,
which is what §7.1.3 asks for ("run once per eclipse, cached") and what
makes even the daily run cheap.

Finally, `SourceRunner` runs the due POLLED sources **before** the due
COMPUTED ones. The cache does not help on the pass that fills it, so a
fresh install still has one pass that can spend minutes sampling paths;
ordering decides what a pass that runs out of budget loses. Local astronomy
is recomputed from the same ephemeris next pass, while a skipped fetch is
the only way fresh aurora, comet and EONET data was ever going to arrive.
§6.2 calls the registry's order irrelevant, so the runner is free to pick
one — and has to, for that to keep being true.

## Consequences

- A new occurrence at the far edge of a 3-year horizon appears up to a day
  after it enters the window, instead of up to 15 minutes. It is three years
  out; §9's leads are measured in days.
- The periodic pass is bounded again, so the POLLED sources behind the
  COMPUTED ones actually run, and §10.2's replan happens from the periodic
  path as designed.
- One case stays unbounded by design: the first pass on a fresh install,
  where nothing is cached. It is why the POLLED sources go first. A pass
  killed part-way through `EclipseSource.refresh` persists nothing and
  re-samples from scratch next time — acceptable because Android reaches
  that state through onboarding's foreground `force`, which has no worker
  budget, and because §7.1.3 already budgets the cold run and asks for UI
  progress. Persisting partial sampling progress would be the fix if a
  device is ever found that cannot finish it.
- `Schedule.OnHorizonChange` is now a name for a cadence chosen to match how
  fast the horizon moves, not for an absence of one. A source that wants a
  different granularity should say so with `Schedule.Periodic`.
- A horizon-years change in Settings still needs an explicit `force` to take
  effect immediately — waiting up to a day for a setting the user just
  changed would read as a bug. There is no such setting UI yet; whichever
  screen adds one owns that call.

## Alternatives considered

**Keep forcing every pass, but only cache the eclipse paths.** Fixes the
runtime cliff (the coarse scan is the expensive part) but not the
`fetched_at` churn, and leaves three other COMPUTED sources recomputing and
re-upserting several hundred rows every 15 minutes for a horizon that moved
by 15 minutes.

**Force only when the horizon's far edge has moved by ≥ 1 day**, as the
issue suggests. Same cadence, but it puts the bookkeeping in every frontend
and leaves `SourceRunner` with a scheduling model that cannot express what
its own sources need. Giving the schedule a due time keeps the decision in
one place.

**Drop `OnHorizonChange` for `Schedule.Periodic(1.days)`.** Equivalent
behaviour, but the distinction is worth keeping: `OnHorizonChange` also
marks the sources that a horizon/settings change must `force`, which a bare
interval does not.
