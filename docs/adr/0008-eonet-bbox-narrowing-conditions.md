# ADR 0008: The EONET bbox narrows only a clustered, travel-bounded setup

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

**"Max travel radius" is not a bound on the rules that matter.**
`maxTravelKm` — the loosest `ReachableWithin` across enabled rules (§6.1) —
is what §7.7 pads with, and it does make the box a superset of everything a
*travel-bounded* rule could match. But it is derived from all enabled rules
at once, so its being non-`null` says only that *some* rule sets a radius.
Two cases slip through:

- It is `null` when no enabled rule uses `ReachableWithin` at all, and §7.7
  doesn't say what to pad with then. Padding with nothing would draw the box
  through the saved locations themselves.
- A rule that matches terrestrial events with no distance condition — "any
  volcano, anywhere" — is bounded by nothing, yet an eclipse rule's 500 km
  is enough to make `maxTravelKm` non-`null` and draw a box that excludes
  the events that rule exists to catch.

Both lose events, and unlike a slow request, they lose them silently: the
bbox filters server-side, so the occurrence never reaches the database and
nothing downstream can tell it was ever there.

## Decision

Send a `bbox` only when **every** saved location is within 2 000 km of
**every** other (and there are at least two), and only when **every enabled
rule that sees `TERRESTRIAL` occurrences can only match within some finite
distance** (and at least one such rule exists); otherwise fetch unnarrowed,
as before this was implemented. `core/sources/EonetBbox.kt` owns both
conditions and EONET's axis order.

The second condition is a new `DerivedThresholds.terrestrialRulesAreTravelBounded`,
computed alongside §6.1's thresholds: a rule is travel-bounded when its
condition tree implies an upper bound on `travelDistanceKm` — `ReachableWithin`
supplies one (`TerrestrialVisibilityModel` never reports local visibility,
§8.8, so for these rules it is a pure distance test), `And` inherits the
tightest of its children's, `Or` needs one on every branch, and `Not`
supplies none. The padding stays `maxTravelKm` exactly as §7.7 specifies:
being the max across all rules, it is never smaller than any terrestrial
rule's own bound.

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
- A single terrestrial rule with no distance condition turns narrowing off
  for the whole app, however tight the location cluster is. That is the
  intended trade: such a rule is a standing request to be told about events
  anywhere, and a request the user can still make.
- The boundedness test is deliberately conservative — `Not` yields no bound
  even where a human could argue one, and a rule the engine could never
  match still counts. Every inaccuracy costs at most a wider response.

## Alternatives considered

- **Implement §7.7 literally** (gate on the nearest pair, pad with 0 when
  `maxTravelKm` is `null`). Rejected: it sends globe-sized boxes for no
  saving, and the zero-padding case loses nearby events silently, which is a
  far worse failure than a slightly larger response.
- **Redefine `maxTravelKm` itself to be `null` unless every terrestrial rule
  is bounded.** Rejected: §6.1 defines that field as "max over
  `ReachableWithin.km` across all enabled rules", and quietly giving it
  different contents would deviate from the doc where an added field does
  not.
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
