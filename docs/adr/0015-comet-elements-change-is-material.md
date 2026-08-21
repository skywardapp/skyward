# ADR 0015: a comet's orbital elements changing is a material change

**Status:** Accepted (closes a gap between §6.3 and §8.6, issue #106)

## Context

`CometVisibilityModel.evaluate`'s `bestViewingStart`/`bestViewingEnd`
(`CometLocal`, §8.6) are the first/last hour the comet is above the
horizon, computed via `altitudeAt` → `heliocentricPosition(payload.elements,
instant)`. They depend on the comet's full orbital elements, not just on
`occ.peakTime`.

`isMaterialChange` (§6.3) checks only `peakTime`, eclipse/lunar `kind`, and
aurora `kpForecast`. It never looks at `CometPayload.elements`, and has no
way to look at `CometLocal.bestViewingStart` at all — that field lives on
`VisibilityResult`, not on `Occurrence`, and `isMaterialChange` only ever
sees two `Occurrence`s.

JPL's monthly SBDB refresh (§7.4) can republish a refined orbit solution
that shifts a comet's position enough to move `bestViewingStart` by a few
minutes, without moving `peakMagDate` past §6.3's 5-minute threshold — the
#60 fix (ADR-less; see `CometSource.buildCometOccurrence`, §7.4.3) makes
`peakMagDate` itself stable across refreshes, but says nothing about
position. §9.1 resolves `BEST_VIEWING` from `CometLocal.bestViewingStart`,
and ADR 0013 resolves that anchor once per occurrence and reuses it for
every match — assuming (Consequences section) that "if the highest-quality
location itself changes between refreshes ... [that is because] the
occurrence's own data changed materially." For comets, an elements
refinement today is *not* material, so that assumption is false: the
anchor — and therefore the §9.3 dedup key — drifts on every refresh that
refines the orbit, and nothing in the pipeline recognizes it as a change
worth acting on. The old key's PENDING row is silently cancelled (§10.4:
no longer desired) and a new key's row is planned in its place.

## Decision

Extend `isMaterialChange` (§6.3) to also treat a change in
`CometPayload.elements` as material.

`CometElements` is a plain data class of `Double`/`Instant` fields,
populated directly from the parsed SBDB response
(`CometSource.buildCometOccurrence`, `full-prec=1`) with no rounding or
derivation in between. It therefore does not change at all between two
fetches unless JPL has republished a genuinely different orbit solution —
so plain structural equality is the right test here, unlike `peakTime`'s
5-minute threshold: there is no float-noise band to tune a tolerance
against.

`CometMagParams` (`m1`/`k1`) is deliberately left out of the comparison:
those fields drive predicted magnitude only, never position, so they
cannot move `bestViewingStart` — the same "display-only, doesn't affect
scheduling" reasoning that already excludes `magAtIngest`.

## Alternatives considered

- **Compare `CometLocal.bestViewingStart` directly.** Rejected:
  `bestViewingStart` is not a field of `Occurrence` — it is a per
  `(occurrence, location, now)` output of `CometVisibilityModel.evaluate`.
  `isMaterialChange` runs inside `SourceRunner.upsertOccurrences`, which
  has neither a location nor a `VisibilityContext` to evaluate against;
  wiring one in would mean re-running the visibility model from the
  source-upsert path, which nothing else in the pipeline does.
- **Persist the resolved `BEST_VIEWING` anchor once and reuse it until a
  material change** (the issue's other suggested direction). Rejected for
  this fix: `CometVisibilityModel` deliberately recomputes
  `bestViewingStart` against *tonight's* darkness window once `peakTime`
  is in the past (§8.6 — "how does this look tonight" once the peak has
  passed), so freezing a persisted anchor would suppress that intentional
  post-peak behaviour, not just the refresh-driven drift this issue is
  about. It would also need a new persisted column nothing today has.
  Extending `isMaterialChange` stays inside the comet-source concern the
  issue itself scopes this to, and reuses the existing §10.4
  cancel-and-replan path instead of adding a new one.

## Consequences

- A genuine SBDB orbit refinement is now material: the planner
  deliberately cancels and re-plans the affected PENDING rows (§10.4)
  instead of the old dedup key silently going stale. Same contract §6.3
  already applies to `peakTime`, `kind`, and `kpForecast` — "getting this
  list wrong produces notification storms," now with one more entry.
- Does not address the separate day-to-day drift `bestViewingStart` has
  after peak, from being evaluated against `ctx.now` instead of a fixed
  instant (`CometVisibilityModel.evaluate`). That is the model's intended
  live "how does it look tonight" behaviour, not a refresh artifact, and
  is out of scope per issue #106's own scope note.
- `CometMagParams` stays outside materiality, same as `magAtIngest`.
