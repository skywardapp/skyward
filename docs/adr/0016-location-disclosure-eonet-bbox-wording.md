# ADR 0016: The location disclosure admits the EONET bbox instead of denying it

**Status:** Accepted

## Context

§10.2's location prominent disclosure and its Android implementation
(`LocationPermissionFlow.kt`) promised: *"Your location is used on-device
only and never transmitted anywhere."* The Play Store listing
(`fastlane/metadata/android/en-US/full_description.txt`) repeated the same
claim in its own words: *"Your saved locations never leave your device."*

Neither statement survives contact with §7.7. `EonetSource` appends a
`bbox=` parameter to its NASA EONET requests, and `eonetBbox()`
(`core/.../sources/EonetBbox.kt`, tightened by ADR 0008) builds that box
from **every saved location**, not just the one just granted through the
permission this disclosure precedes. A GPS fix accepted through the very
dialog making the promise becomes a `SavedLocation` that can shape a box
sent to `eonet.gsfc.nasa.gov` (and lands in that service's request logs).
With a small `ReachableWithin` radius the box narrows to within tens of km
of the user's actual locations — not their exact coordinates, but far more
than "never transmitted anywhere" admits.

This is a spec-internal contradiction, not a code bug: §7.7 correctly
describes what ships, and §10.2 correctly describes what Play requires a
disclosure to cover, but the exact wording the doc mandates for that
disclosure is factually wrong once §7.7 is implemented. Issue #64 offered
three ways to close the gap: quantize the bbox to a coarser grid, make bbox
narrowing opt-in, or correct the disclosure text. The first two change
§7.7's shipped behaviour — including reducing the EONET payload-size
optimization by requiring a settings surface — for a promise ("on-device
only") that a bbox derived from saved locations can never actually satisfy
even at coarse granularity, since *some* location-derived data would still
leave the device either way. Only correcting the text closes the gap
without touching working, ADR-0008-hardened code for a payload optimization
nothing else in the issue calls into question.

## Decision

Reword the disclosure — both the Android dialog and §10.2's mandated
wording — to state what actually happens: location stays on-device
*except* for an approximate region, derived from all saved locations and
never exact coordinates, optionally included in NASA EONET queries to
narrow that source's response. Reword the Play Store listing's privacy
paragraph the same way. Leave `EonetBbox.kt` and ADR 0008 untouched — §7.7's
bbox is spec-sanctioned and the fix is that the disclosure now says so.

The new wording keeps the properties users actually rely on: no analytics,
no account, no data ever leaving the device to anywhere other than the
three named public data providers (P1), and no exact coordinates ever
transmitted (the bbox is a region padded by a travel radius, ADR 0008's
whole point). What it stops claiming is that *no* location-derived data
ever reaches those providers, which was never true of `EonetSource` and
`AuroraSource` alike (Kp/OVATION requests carry no location, but the
broader "on-device only" framing invited the same misreading there too —
this ADR's wording is deliberately about EONET specifically, since that is
the one source location shapes).

## Consequences

- The disclosure, the design doc, and the Play listing now agree with §7.7
  instead of contradicting it. No behaviour changes: the bbox still narrows
  exactly as ADR 0008 specifies.
- The dialog is a few words longer. Play's prominent-disclosure requirement
  is about *accuracy*, not brevity, so a correct sentence is a better
  outcome than a shorter, wrong one.
- A future change to `EonetSource`'s narrowing (e.g. adopting one of the
  other two options from issue #64) still needs a wording update here, but
  that is one string plus one Play listing paragraph, not a design-doc
  contradiction to rediscover.

## Alternatives considered

- **Quantize the bbox to a coarse fixed grid.** Reduces precision but does
  not make "never transmitted" true — a snapped-to-5° box is still
  location-derived data leaving the device, so the disclosure would still
  need correcting, and the codebase would take on complexity in
  `EonetBbox.kt` (and its ADR-0008-pinned test fixtures) for no change in
  what the disclosure is allowed to claim.
- **Make bbox narrowing opt-in.** Would let "never transmitted" stay true by
  default, at the cost of a new settings surface, a design-doc edit to
  §7.7's automatic-narrowing behaviour (not just an ADR), and losing the
  payload optimization for most users who would not think to opt in.
  Rejected as disproportionate to a wording bug.
- **Leave §10.2's wording and drop the bbox parameter entirely.** Keeps the
  promise trivially true but reopens D2/§7.7's payload-size rationale for
  EONET, which issue #64 does not ask for and ADR 0008 already resolved
  once.
