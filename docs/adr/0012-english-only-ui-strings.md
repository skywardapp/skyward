# ADR 0012: v1 ships English-only, with UI strings in Kotlin

**Status:** Accepted for v1; revisit at the first credible translation offer

## Context

Every user-facing string in both frontends is a Kotlin literal.
`androidApp/src/main/res/values/strings.xml` contains one entry (`app_name`)
and there is not a single `stringResource` call in `androidApp/src/main`;
`core/format/NotificationCopy.kt` composes §10.5's notification text in
English by construction; the desktop app has no resource mechanism at all.
Pluralization is hand-rolled English (`ScheduleFields.formatLead`,
`WhileYouWereAwayBanner`, `core/format`'s `plural`-style helpers), which is
correct for English and wrong for the several languages whose plural rules
have more than two forms.

The design doc does not specify localization either way, which is how this
came to be a default rather than a decision. It deserves to be one:
F-Droid's audience is disproportionately non-English-speaking and
disproportionately willing to contribute translations, so "English-only" is
a position with a cost, not a neutral starting point.

The cost of changing it later is not evenly distributed:

- **Android chrome** is the cheap part — extraction into `strings.xml` plus
  `plurals` resources is mechanical, and the platform does the rest.
- **The desktop app** has no equivalent. Compose for Desktop ships no
  resource loader; localizing it means choosing one (moko-resources,
  Compose Multiplatform resources, or a hand-rolled `Map` per locale) and
  wiring it through, and any dependency added has to clear §16's licence
  allowlist and §15.3's "commonMain stays pure" rule first.
- **Notification copy** is the expensive part and the part users would feel
  most. §10.5's strings are *composed*, not templated: quality, travel
  distance, direction, times and location name are assembled into sentences
  whose word order is English. Translating them means rewriting §10.5 as
  parameterized templates with per-language ordering, and re-pinning every
  `NotificationCopyTest` expectation per locale.

## Decision

Ship v1 in English, with strings where they are. Do not extract to
`strings.xml`, do not add a resource framework to the desktop app, and do
not build a pluralization layer for a language that does not need one.

Revisit when there is a *concrete* trigger — someone offering a translation,
or a decision to target a specific locale — rather than on principle. At
that point the order is: pick the desktop mechanism first (it constrains
everything else), rewrite §10.5's composition into templates second, and
extract the chrome last, because the chrome is the part that stays easy.

## Consequences

- Every copy fix is a code change and a rebuild, for both frontends. That is
  the price already being paid; this ADR only makes it visible.
- The hand-rolled English pluralization stays. It is marked at each site
  with a pointer here, so a future translator finds the list rather than
  discovering it string by string.
- No user-visible commitment is made either way: nothing in the app claims
  to be translatable, and no half-migrated resource layer is left behind for
  someone to finish.

## Alternatives considered

- **Extract Android's strings now, leave desktop as-is.** Half the app
  localizable, a `strings.xml` that drifts from the desktop literals it
  mirrors, and no user able to read the app in their language until the
  harder half lands. Motion without delivery.
- **Adopt Compose Multiplatform resources across both frontends now.**
  Defensible, and the likely eventual answer — but it is a dependency and a
  build-time codegen step (§15.4 cares about the second) taken on for a
  feature nobody has asked for yet.
