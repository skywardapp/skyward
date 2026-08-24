# ADR 0024: Android's drawn views use a fixed palette, not Material You

**Status:** Accepted

## Context

§13 opens with "Material 3, Jetpack Compose, **dynamic color**", and the
Android app follows it: `SkywardTheme` builds its scheme from
`dynamicDarkColorScheme`/`dynamicLightColorScheme` on API 31+, and
`ui/common/QualityColor.kt` resolves the quality ramp against
`MaterialTheme.colorScheme` so the badges match their surroundings on
whatever wallpaper the user has.

ADR 0022 brings §14's four drawn views to Android. Their colours are not
decoration in the same sense:

- §14.4's aurora overlay and Kp gauge are coloured by NOAA's own G-scale
  bands, which run green → yellow → orange → red by convention. `OvationRamp`
  encodes that ramp as packed ARGB and is shared with the desktop.
- §14.2's timeline draws "one lane per phenomenon", eight of them, that have
  to stay distinguishable from each other.
- §14.1's map layers, and §13.3's mini-map, need the eclipse track to read as
  an eclipse track against land and ocean fills.

A wallpaper-derived scheme has no way to honour any of that. It supplies a
small number of tonally-related roles, so on one device the aurora overlay
comes out pink and the eight lanes collapse toward each other.

## Decision

The drawn views take their colours from `ui/chart/ChartPalette.kt`, a fixed
set of hues mirroring `:desktopApp`'s `SkywardPalette`. Everything else on
Android — every existing screen, and the Material surfaces these charts sit
on — keeps dynamic colour exactly as §13 specifies.

The palette still resolves light/dark, and does so through
`LocalChartDarkTheme`, a `CompositionLocal` that `SkywardTheme` fills with
the same resolved `dark` it hands to Material. Reading
`isSystemInDarkTheme()` directly would have been wrong: §11's `theme`
setting can override the OS, and a chart consulting the system value would
contradict the surface it is drawn on the moment a user sets
`ThemeChoice.LIGHT` or `DARK`.

The values are written out rather than derived, and match the desktop's
literal for literal, so the same event is the same colour on both apps.

## Consequences

- The charts will not match a user's wallpaper the way the rest of the app
  does. That is the intended trade: a legend that means the same thing on
  every device is worth more here than tonal harmony with the surface.
- `SkywardPalette` (desktop) and `ChartPalette` (Android) are two copies of
  the same numbers, and can drift. They cannot be one copy —
  `androidx.compose.ui.graphics.Color` may not enter `:core` (§15.3), which
  `SkywardTheme.kt`'s own comment already notes about its fallback scheme.
  `OvationRamp`, the one ramp that is *not* a Compose `Color` (it is packed
  ARGB), did move to `:core` and is genuinely shared.
- §13's "dynamic color" is now true of the app minus four screens. This ADR
  is the record of that exception.

## Alternatives considered

- **Use `MaterialTheme.colorScheme` throughout, as `QualityColor.kt` does.**
  Consistent, and correct for badges and chips. It cannot express eight
  distinguishable lanes or NOAA's band colours, so it fails the views this
  ADR is about.
- **Derive chart hues from the dynamic scheme** (rotate hue per phenomenon
  off `colorScheme.primary`). Keeps some wallpaper harmony and guarantees
  separation, but the aurora would still not be green and the G-scale would
  still not be NOAA's, so the meaningful half is lost for the decorative
  half.
- **Move the palette to `:core` so both apps share it.** Blocked by §15.3.
  The nearest legal version — packed ARGB `Int`s in `:core`, wrapped in
  `Color` by each app — is what `OvationRamp` does, and would work for the
  rest. Not done here because `SkywardPalette` also resolves light/dark
  through a Compose `CompositionLocal`, so only the literals would move; the
  duplication that remains would be the same size. Worth revisiting if a
  third frontend appears.
