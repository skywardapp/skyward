# ADR 0012: ComposeNativeTray is pinned at 1.3.3, not §15.2's `1.1.x`

**Status:** Accepted

## Context

§15.2's version table pins ComposeNativeTray at `1.1.x`.
`gradle/libs.versions.toml` pins `composeNativeTray = "1.3.3"`. Every other
row in that table matches the build exactly, so this single mismatch reads as
a slip rather than a decision — and §15.2's own instruction ("pinned exactly,
updated deliberately, in lockstep") means it should have a record either way.

The upstream history explains it. `io.github.kdroidfilter:composenativetray`
released `1.1.0` as the *only* release in the `1.1.x` line, then moved on
through `1.2.0` to the `1.3.x` series; `1.3.3` was the current release when
desktop packaging (M6) was built, and is still the latest under that
coordinate. §15.2's row is therefore a snapshot of what was current when the
design doc was written, not a constraint that something in the app depends
on.

`1.3.3` is not, however, the newest build of this library anywhere. The
project has since republished under a **new group coordinate**,
`dev.nucleusframework:composenativetray`, whose `2.x` line reaches `2.1.0`
(Maven Central metadata, `lastUpdated` 2026-08-16, against 2026-06-19 for the
old coordinate). Moving there is a coordinate change *and* a major-version
change at once, which is its own §15.2 "updated deliberately" decision with
its own migration and re-test — not something to fold into an ADR whose
subject is a stale row in a table. It is deferred rather than rejected: R8's
containment (a small interface plus §10.3's no-tray degradation path) is what
makes deferring safe, and is equally what would make the migration cheap when
someone takes it on.

Following the row literally would mean shipping a tray implementation three
minor releases behind, on the one dependency §19's R8 already flags as a
maintenance risk ("ComposeNativeTray / two-slices maintenance status"), and
whose failure mode is a broken StatusNotifierItem on exactly the desktops the
app targets. That is the wrong direction to move for a version-table match.

## Decision

Pin `1.3.3` — the version the app is actually built and tested against — and
record the deviation here rather than downgrading to `1.1.0`.

The pin stays exact, per §15.2: no version ranges, no `+`, and updates happen
as deliberate commits. This ADR is what a future reader finds when the table
and the catalog disagree.

## Consequences

- §15.2's table is, on this row, out of date with respect to upstream. Treat
  `gradle/libs.versions.toml` as authoritative for the pin and this ADR as
  the reason.
- The app stays on a coordinate whose last release predates the move to
  `dev.nucleusframework`. That is a known, bounded debt with a named
  successor, which is better than the undocumented mismatch it replaces.
- R8's mitigation is unchanged: the tray sits behind a small interface with a
  no-tray degradation path (§10.3), so a future upstream break is contained
  regardless of which 1.x is pinned.

## Alternatives considered

- **Downgrade to `1.1.0`.** Matches the table; ships known-older tray code
  against the risk R8 names, with nothing gained.
- **Amend §15.2's row to `1.3.x`.** The design doc is the specification and
  is not edited to match the code (AGENTS.md); a deviation gets an ADR.
- **Migrate to `dev.nucleusframework:composenativetray` 2.1.0 here.** The
  right eventual move, and out of scope for a documentation fix: a new
  coordinate and a major version need their §16 licence check, an API-break
  review and a run against a real StatusNotifierItem host, none of which this
  change is carrying.
