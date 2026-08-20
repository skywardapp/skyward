# ADR 0008: The EONET bbox narrows only a fully clustered set of locations, and only with a travel radius

**Status:** Accepted (tightens two conditions §7.7 states loosely)

## Context

§7.7's third bullet asks for a request-narrowing bounding box:

> If ≥ 2 saved locations are within 2 000 km of each other, pass a `bbox`
> covering all saved locations + max travel radius to cut payload; **EONET
> bbox order is nonstandard: `bbox=minLon,maxLat,maxLon,minLat`**.

Two of its terms don't survive contact with an implementation.

**The gate and the box disagree about which locations they mean.** The gate
looks at *some pair* of saved locations ("≥ 2 ... within 2 000 km of each
other"); the box has to cover *all* of them. With a home and a cabin 200 km
apart plus one location on another continent, the gate passes and the box
still has to stretch across the ocean — a `bbox` that excludes essentially
nothing, sent for no gain. Worse, the closer the box gets to the whole globe
the more the narrowing is pure risk: everything it can still do is drop
events.

**A bbox without a travel radius is not safe to draw.** `maxTravelKm` — the
loosest `ReachableWithin` across enabled rules (§6.1) — is what makes the
padded box a superset of everything a rule could match. It is `null` when no
enabled rule uses `ReachableWithin` at all, and §7.7 doesn't say what to pad
with then. Padding with nothing would draw the box through the saved
locations themselves, so a wildfire 30 km up the road would never reach the
database — and, unlike a slow request, that failure is silent.

## Decision

Send a `bbox` only when **every** saved location is within 2 000 km of
**every** other (and there are at least two), and only when `maxTravelKm` is
non-`null`; otherwise fetch unnarrowed, as before this was implemented.
`core/sources/EonetBbox.kt` owns both conditions and EONET's axis order.

Shapes the two-corner box cannot express are widened rather than
approximated: a cluster whose padding wraps the antimeridian, or reaches far
enough north or south to enclose a pole, keeps its latitude band — most of
the saving — and gives up on longitude. Edges are rounded to three decimals
(~100 m) away from the box's interior, so the rounding that keeps requests
byte-identical between runs (§17.6) can only ever add margin.

Both conditions are strictly narrower than §7.7's, so every request this
sends is one §7.7 would also have sent; the difference is the requests it
declines to narrow.

## Consequences

- The optimization engages for the case it was written for — a user whose
  locations are all in one region — and stands down for the cases where it
  would trade a real risk of missing events for an unmeasurable saving.
- `maxTravelKm` now has the consumer §6.1 says it has ("drives EONET bbox
  padding"), so `ThresholdDerivation` is no longer computing it for nobody.
- Users with a distant outlier location keep the full-planet response. That
  is the status quo, and §7.7's own framing ("to cut payload") makes payload
  size the only thing at stake.
- A rule that matches terrestrial events *without* a `ReachableWithin`
  condition is still bounded by another rule's travel radius when one is
  enabled. That is what §6.1 specifies (`maxTravelKm` is the single derived
  padding), and it is only reachable for a user who has both kinds of rule
  and a tight location cluster; if it ever bites, the fix is to derive the
  padding per phenomenon rather than to loosen the box.

## Alternatives considered

- **Implement §7.7 literally** (gate on the nearest pair, pad with 0 when
  `maxTravelKm` is `null`). Rejected: it sends globe-sized boxes for no
  saving, and the zero-padding case loses nearby events silently, which is a
  far worse failure than a slightly larger response.
- **Cluster the nearby locations and narrow to that cluster only.**
  Rejected: it drops the outlier's events outright — the box must cover
  every saved location, which is what §7.7 says.
- **Pad with a fixed radius when no rule sets one.** Rejected: any constant
  would be invented here rather than derived from what the user asked for,
  and §16/§7.7 give no basis for one.
- **Skip the feature and record the deviation** (the other option issue #26
  offers). Rejected: `maxTravelKm` exists specifically to drive this, and
  deferring it also defers the test that pins EONET's nonstandard axis
  order — the part of the bullet most likely to be got wrong later.
