# ADR 0004: Comet apparent magnitude is `M1 + 5*log10(delta) + K1*log10(r)`, not `+ 2.5*K1*log10(r)`

**Status:** Accepted (implementation correction, not a reopening of D1–D13)

## Context

Design doc §7.4.2 states the comet total-magnitude formula as:

> `m = M1 + 5*log10(delta) + 2.5*K1*log10(r)`

`OccurrencePayload.kt`'s `CometMagParams` doc comment and `Kepler.kt`'s
`apparentMagnitude()` both implemented this verbatim.

This is the standard IAU/MPC comet-magnitude law written in its more common
form `m1 = M1 + 5*log10(delta) + 2.5*n*log10(r)`, where `n` is the
"activity index" (typically 2–6). The confusion is that JPL SBDB's `K1`
field — the source of this app's magnitude parameters (per NOTICE) — is
**not** `n`; it is MPC's `K1`, which is already `2.5*n`. Applying a further
`2.5*` multiplies the slope term by 2.5 twice, exaggerating the predicted
brightness swing between aphelion and perihelion.

Confirmed independently, not just against CodeRabbit's review claim:
- A live [Stellarium issue](https://github.com/Stellarium/stellarium/issues/4602)
  ("Wrong comet magnitudes are reported") describes exactly this bug from
  the other direction: Stellarium's own formula didn't apply the `2.5*`,
  and the reporter notes "the K1 value used by Horizons is approximately
  2.5 times larger than what Stellarium's formula expects" — i.e. Horizons/
  SBDB's `K1` already has the factor baked in.
- MPC's published cometary-elements format documents the total-magnitude
  law as `T-mag = M1 + 5*log10(delta) + K1*log10(r)` directly in terms of
  `K1`, with no separate `2.5*` factor applied by the consumer.

## Decision

`Kepler.kt`'s `apparentMagnitude()` uses `mp.k1 * log10(r)`, not
`2.5 * mp.k1 * log10(r)`. `CometMagParams`'s doc comment is corrected to
match.

## Why this isn't a D-decision reopening

None of D1–D13 specify the magnitude formula's exact coefficients; §7.4.2
is an implementation detail the app's own JPL SBDB data source (not
COBS, excluded by D12) makes directly checkable. Same spirit as ADR
0001–0003: verify a design-doc formula against the real data source it's
meant to consume, rather than trust the doc's restatement of it.
