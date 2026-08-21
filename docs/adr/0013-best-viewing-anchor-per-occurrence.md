# ADR 0013: the BEST_VIEWING anchor is resolved once per occurrence, not per location

**Status:** Accepted (resolves a tension between §9.1 and §9.3)

## Context

§9.3 fixes the notification identity key at `(occurrenceId, anchorTime,
lead)` for one stated reason:

> This prevents double-buzzing for "Home" and "Office" 10 km apart.

That works because `PEAK` and `WINDOW_START` resolve to occurrence-level
instants — every saved location's match yields the same `anchorTime`, so
the key collapses them into one notification whose body quotes the
best-quality location.

`BEST_VIEWING` does not behave that way. §9.1 resolves it from
`MeteorLocal.bestViewingStart` / `CometLocal.bestViewingStart`, which
`MeteorShowerVisibilityModel` and `CometVisibilityModel` derive from *that
location's own* solved darkness window. Two locations 10 km apart solve
dusk a minute or two apart, so they produce different `anchorTime` values,
different keys, and two notifications minutes apart — for every lead, of
every peak. The shipped rule "Major meteor showers, decent conditions"
(§9.6) is anchored `BEST_VIEWING`, so this is the default experience for
anyone with two saved locations, not an exotic configuration.

So §9.3's guarantee, as written, holds only for the two occurrence-level
anchors. Nothing in the doc reconciles the two sections; the key format
itself is what creates the tension.

## Decision

Resolve the `BEST_VIEWING` anchor **once per occurrence**, from the
highest-quality matching location, and use that single instant for every
match on that occurrence (`Planner.bestViewingAnchorsByOccurrence`).

The highest-quality location is deliberately the same one §9.3 already
picks for the notification body ("body shows the best (highest-quality)
location"). One notification therefore names one location, quotes that
location's viewing window, and fires at a lead measured from *that*
window — anchor and copy cannot disagree.

Rejected alternatives:

- **Bucket the anchor into the key** (round to the nearest 30 min). Two
  locations still split whenever they straddle a bucket boundary, so it
  narrows the failure window instead of closing it, and it leaves the
  notification firing off one arbitrary location's window while quoting
  another's.
- **Drop `anchorTime` from the key for `BEST_VIEWING`** (key on
  `(occurrenceId, lead)`). It dedups correctly, but a `BEST_VIEWING` rule
  whose anchor falls back to `PEAK` — §9.1's documented fallback for a
  null window or any other payload — would then stop sharing a key with a
  `PEAK`-anchored rule on the same occurrence, re-introducing a
  double-buzz in the case that used to work.

## Consequences

- A user whose two saved locations differ meaningfully in darkness
  (hundreds of km apart, not 10) gets the better location's window rather
  than each location's own. That is the same trade §9.3 already makes for
  the notification *body*, and the alternative is the double-buzz §9.3
  exists to prevent. The Upcoming screen (§13.2) is unaffected: it
  evaluates and displays per location.
- Within one planning pass, every match on the occurrence shares one
  anchor, so §6.3 materiality and §10.4 reconciliation see one key
  instead of as many keys as matching locations that pass produced.
  That guarantee is per-pass, not permanent: if the highest-quality
  location itself changes between refreshes — the occurrence's own data
  changed materially, or a location's quality crossed a threshold — the
  anchor moves with it, and the previous key is superseded like any
  other re-plan. That is §6.3's ordinary re-plan behaviour, the same
  thing that already happens to a PEAK-anchored notification when
  `peakTime` itself changes; this ADR does not claim to suppress it.
- Ties in "highest-quality location" break on `matches`' iteration
  order (§9.2: occurrences outer, locations inner), the same tie-break
  `desiredNotifications` already uses to pick the location the
  notification *body* quotes. A different tie-break for the anchor
  alone would let the anchor and the body disagree about which location
  "won" — the one failure mode this ADR exists to prevent.
- §9.3's key *format* is untouched — this changes which instant fills the
  `anchorTime` slot for one anchor type, not the natural-key design
  (§6.4, §10.4) the §17.6 determinism guard protects.
