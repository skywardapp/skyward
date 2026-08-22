# ADR 0017: Aurora NOWCAST first-seen notifications get a per-rule cooldown, not a phenomenon-aware dedup key

**Status:** Accepted (implementation correction, not a reopening of D1–D13)

## Context

Issue #57 found two interacting defects in the shipped "Aurora NOW" rule
(§9.6):

1. It shipped `quietHours = QuietHours(0, 6)`, contradicting §9.6's own
   table text ("quietHours 00–06 **off by default**"). Because a NOWCAST
   occurrence's window is only `forecastTime..+1h` (§7.3.3), `Planner`'s
   quiet-hours defer (`applyQuietHours`) always lands past `occ.window.end`
   for a match inside 00:00–06:00 device-local, so the notification is
   dropped outright (§9.1's documented drop-vs-defer split for FORECAST
   occurrences) — not delayed to 06:00 as a reader of the rule's *name*
   would expect. Net effect: zero aurora-NOW alerts during the hours
   magnetic-midnight activity is most likely.

2. `AuroraSource.buildNowcastOccurrence` deliberately mints a new occurrence
   id every OVATION fetch (`au:now:<forecastTime>`) — §7.3.3 says so in so
   many words ("one occurrence per OVATION fetch"), specifically so a
   still-active aurora keeps being re-evaluated against the freshly-fetched
   grid rather than being treated as a revision of a stale row. But §10.4's
   first-seen dedup key is `(occurrenceId, anchorTime, "first")` — keyed on
   the occurrence — so a churning occurrence id defeats it entirely. An
   aurora visible for six hours at the 15-minute active-tier poll interval
   (§7.3.2) produces roughly 24 separate first-seen notifications, one per
   poll.

Fix 1 is a straightforward field change (`quietHours = null` on the shipped
rule). Fix 2 needs a real design decision: how does the planner recognize
"this is the same ongoing alert" across occurrences that are, by design, not
the same occurrence?

## Decision

Add `NotifySchedule.firstSeenCooldown: Duration?` (default `null`, so every
other rule is unaffected) and `Planner.applyFirstSeenCooldown`: a
post-processing step over `desiredNotifications`'s output that drops a
`notifyOnFirstSeen` candidate when a previous notification for the same
`(ruleId, locationId)` already fired within the cooldown window. Ship the
default "Aurora NOW" rule with `firstSeenCooldown = 2.hours`.

The cooldown key is deliberately just `(ruleId, locationId)`, with no
attempt to compare the *strength* of the current occurrence against the
previous one (e.g. "only suppress if Kp hasn't increased"), for two reasons:

- The previous NOWCAST occurrence is gone by the time the comparison would
  run. `SourceRunner.upsertOccurrences` (§6.3) drops withdrawn FORECAST rows
  every refresh, and the previous nowcast's id is never re-fetched (each
  fetch mints a fresh one) — it is withdrawn on the very next poll. There is
  nothing left in `occurrence` to compare the new Kp against.
- Persisting that comparison value would mean adding an aurora-specific
  field (Kp, or some generic "escalation" number) to `PlannedNotification`,
  a table every phenomenon shares. §5's design note keeps phenomenon data
  inside the sealed `OccurrencePayload` precisely so common tables and code
  paths don't need to know about it; a `kp` column that only NOWCAST ever
  populates would be exactly the leak that note warns against.

A time-only cooldown is a deliberate, named simplification against the
issue's "same/lower Kp" suggestion: for the six-hour-persisting-aurora
failure scenario the issue reports, it fully fixes the spam (one alert every
two hours instead of one every fifteen minutes), and it costs only a
capped, bounded delay — at most one cooldown window — before a
*strengthening* aurora re-alerts, rather than never re-alerting at all.

## Consequences

- `Planner.desiredNotifications` stays pure and unaware of `previous`
  (its own doc comment's existing invariant); the cooldown is a separate
  function `ReplanCoordinator.replan` composes in, the same shape as the
  existing mute-suppression step.
- `NotifySchedule` gained a field with no UI control on Android yet
  (`RuleEditorScreen`'s `ScheduleDraft` doesn't expose it). `toRule()`
  preserves it from the rule being edited, the same way it already
  preserves `hidden` — so editing "Aurora NOW" via the Android app (e.g. to
  change its leads) does not silently drop the cooldown and reintroduce the
  bug this ADR fixes. Desktop's `RulesScreen` edits `NotifySchedule` via
  `.copy()` in place and preserves it automatically.
- A location whose best-matching aurora location flips between two nearby
  saved locations poll-to-poll would not be caught by the same cooldown
  bucket (keyed by `locationId`). Accepted: the issue's failure scenario is
  single-location, and over-suppressing across genuinely different places a
  user cares about would be a worse trade.

## Alternatives considered

**Change the NOWCAST natural key so the same aurora "episode" keeps one
occurrence id** (e.g. bucket by a coarser time grid, or key on
`(location, activity-continuity)` instead of `forecastTime`). Rejected:
that is exactly the §6.4 contract change the issue itself flagged as
needing its own ADR, and it fights §7.3.3's explicit intent that each fetch
re-evaluates independently against the latest grid — losing that would
mean a strengthening-then-weakening aurora across one "episode" stops
getting freshly re-evaluated visibility.

**Persist the comparison Kp on `PlannedNotification`.** Rejected in Context
above: leaks phenomenon-specific data onto a shared table for one rule's
benefit.

**A global (not per-rule) cooldown setting.** Rejected: cooldown only makes
sense for `notifyOnFirstSeen` rules over churning-identity sources: leads
already dedup correctly per occurrence, and a blanket app-wide setting
would either be a no-op for most rules or need its own exemption list —
more surface than the one rule that needs it.
