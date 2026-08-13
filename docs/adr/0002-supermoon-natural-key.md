# ADR 0002: Supermoon natural key is `sm:<yyyymmdd>`, not `sm:<yyyymm>`

**Status:** Accepted (implementation correction, not a reopening of D1–D13)

## Context

Design doc §6.4's natural-key table gives the supermoon id format as
`sm:<yyyymm>` — "month of full moon" — with the worked example `sm:202611`.

A "blue moon" calendar month can contain two full moons, and both can
independently satisfy §7.5's supermoon definition ("full moon within ±24h of
perigee AND perigee distance < 360,000 km"). This isn't hypothetical:
verified against the US Naval Observatory's published phase table
(`aa.usno.navy.mil/api/moon/phases/year?year=2023`), **August 2023** had full
moons on Aug 1 (18:32 UTC) and Aug 31 (01:35 UTC), and `MoonEventSource`'s
own perigee search independently confirms both qualify as supermoons under
the doc's exact threshold. With a month-only key, the second occurrence's
`INSERT OR REPLACE` (§6.3) would silently overwrite the first in the
database — not a display glitch, an actual lost event and, per §10.4's
dedup-by-occurrence-id scheme, a lost or corrupted notification.

`core/src/commonTest/.../MoonEventSourceTest.kt`'s
`idsAreUniqueAcrossTheRefreshedHorizon` test reproduces this: a 6-year
refresh produces 12 supermoon occurrences but only 11 unique `yyyymm` keys
before this fix.

## Decision

Use `sm:<yyyymmdd>` — the UTC calendar date of the full moon — instead of
`sm:<yyyymm>`. This keeps the key deterministic and derived purely from the
event's own real-world timing (still a natural key, never a random UUID,
per §6.4's stated rationale for natural keys generally), while resolving
same-month collisions. A blue-moon month now produces two distinct ids,
e.g. `sm:20230801` and `sm:20230831`.

## Why this isn't a D-decision reopening

None of D1–D13 mention the supermoon key format; §6.4 is an implementation
specification, not a locked product decision. This ADR documents a
correctness fix to that specification, discovered and verified with real
independently-sourced data during M1, not a preference change.
