# ADR 0015: Aurora 3-day natural key is `au:3d:<slot's own yyyymmdd>:<hour-of-day>`, not `au:3d:<forecast-window-start yyyymmdd>:<slot 0-23>`

**Status:** Accepted (implementation correction, not a reopening of D1–D13)

## Context

Design doc §6.4's natural-key table gives the aurora 3-day forecast id format
as `au:3d:<forecast-window-start yyyymmdd>:<slot>`, where `slot` is a 3-hour
window index 0–23 across the whole 3-day forecast and the date component is
the start of that forecast window (i.e. one fixed date per fetch, not per
slot).

`AuroraSource.buildThreeDayOccurrences` (§7.3.3) instead mints
`au:3d:<slot's own yyyymmdd>:<hour-of-day 00-21>` — the calendar date of the
individual 3-hour slot itself, paired with that slot's hour of day.

A window-start-anchored index is not stable across refreshes. SWPC's
3-day-forecast file is a rolling window: the earliest row it returns changes
as already-elapsed slots age out, so "the forecast-window-start" of today's
fetch is not the same date as yesterday's fetch even though most of the
individual 3-hour slots they describe are the same real-world windows. A key
derived from that rolling start would reassign every slot's `slot` index
(and its `forecast-window-start` component) on the next fetch, changing the
id of a slot that hasn't otherwise changed. Per §6.3's `INSERT OR REPLACE`
semantics that reads as a *new* occurrence rather than a materially-unchanged
one: the old row is orphaned (never revisited, since dropped-and-replaced-FORECAST
cleanup in `SourceRunner.upsertOccurrences` only fires for ids that stop
appearing at all) while a fresh row with a new id triggers a re-plan and,
per §10.4's dedup-by-occurrence-id scheme, risks a duplicate notification for
a Kp window the user was already alerted about.

Keying on the slot's own date and hour-of-day instead ties the id purely to
that slot's real-world timing, independent of which particular fetch
produced it — the same reasoning ADR 0002 already applied to the supermoon
key (§6.4's stated rationale for natural keys generally: "deterministic
across devices and re-fetches"). Two slots on the same calendar day 3 hours
apart get distinct hour-of-day labels, and the day-prefix keeps slots on
different days from colliding, so the format stays unique without needing a
window-relative index at all.

## Decision

Keep `au:3d:<slot's own yyyymmdd>:<hour-of-day, 00-21 step 3>` as the aurora
3-day forecast key, e.g. `au:3d:20260813:18`. Do not switch to the
window-start-plus-0–23-index format in §6.4's table.

## Why this isn't a D-decision reopening

None of D1–D13 mention the aurora 3-day key format; §6.4 is an
implementation specification, not a locked product decision. This ADR
documents a correctness fix to that specification, found during the issue
#77 correctness pass, not a preference change.
