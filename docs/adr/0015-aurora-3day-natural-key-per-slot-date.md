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

## Consequences

- A given 3-hour Kp slot keeps the same id across every refresh that still
  reports it, regardless of where that slot sits in the rolling forecast
  file on any given day — the identity §6.4's natural keys exist for
  (§6.4: "deterministic across devices and re-fetches").
  `SourceRunner.upsertOccurrences` therefore treats a re-fetched,
  unchanged slot as unchanged (`INSERT OR REPLACE` on the same row), not as
  a withdrawal-plus-new-arrival, so `first_seen_at`, the visibility cache
  entry keyed on `fetched_at` (§11), and any already-fired notification
  state for that slot all survive.
- §6.4's own table text is now wrong for this one row; a reader who trusts
  it literally over the code needs this ADR to know why. There is no
  automated check tying `au:3d:` ids to the table (unlike
  `verifyShowerCatalogsMatch` for `showers.json`), so this deviation is
  discoverable only via the code comment pointing here.
- This repository has not shipped a public release yet (README's Status:
  M7, F-Droid/Play submission still outstanding) and aurora occurrences are
  `Certainty.FORECAST` with a `NEXT_ISSUE_APPROXIMATION` (30h) expiry, so
  there is no installed base whose stored `au:3d:` rows need reconciling
  against this format — a fresh SWPC fetch replaces them within a day
  regardless of key shape.

## Alternatives considered

**The literal §6.4 format** (`au:3d:<forecast-window-start yyyymmdd>:<slot
0-23>`). Rejected: see Context above — it is not actually stable across
refreshes, which defeats the one property natural keys are for.

**A window-start anchored to a fixed calendar rule** (e.g. "most recent UTC
midnight") instead of "whatever SWPC's file happens to start at". This
would restore a literal `slot 0-23` index and make the id shorter, but
still requires computing an index relative to that anchor and handling a
slot that doesn't align to a 3-hour boundary from it; the code already has
each slot's own timestamp on hand, and keying directly off it needs no
anchor logic at all.

## Why this isn't a D-decision reopening

None of D1–D13 mention the aurora 3-day key format; §6.4 is an
implementation specification, not a locked product decision. This ADR
documents a correctness fix to that specification, found during issue #77's
correctness pass, not a preference change.
