# ADR 0005: `PeakOnWeekend`/`PeakInLocalHours` use longitude-derived solar time, not an IANA timezone

**Status:** Accepted (implementation gap the design doc's own model left open)

## Context

§9.1 defines two `Cond` variants in terms of "local" time:

> `PeakOnWeekend(includeFridayNight)` — "weekend" = local Fri 18:00-Mon
> 06:00 at the location's timezone when includeFridayNight
>
> `PeakInLocalHours(fromHour, toHour)` — wraps midnight

But `SavedLocation` (§5/§11) carries only `id`, `name`, `point` (lat/lon),
`isPrimary`, `createdAt`, `modifiedAt` — there is no timezone field
anywhere in the schema, and no timezone-boundary dataset is listed in §16
(Natural Earth's *cultural* vectors used by the desktop map, §14.1, are
country/state polygons, not the IANA tzdata polygon set — a meaningfully
larger, separately-licensed bundle the design doc never mentions). Pulling
one in just for two `Cond` predicates would be a disproportionate new
dependency for a rule condition most users won't touch.

## Decision

Approximate "local time" as **local mean solar time**: shift the instant by
`lonDeg / 15.0` hours (15 deg of longitude per hour, the definition of a
time zone's nominal width) and read the hour/day-of-week off that shifted
instant in UTC. This needs no dataset, degrades gracefully (worst case:
±30 min from a real zone's clock time, since real zones snap to
administrative boundaries rather than pure longitude), and is exactly the
same kind of spherical-astronomy approximation §8.1 already commits to for
geodesy ("error is ≤ 0.5%, irrelevant at this app's precision").

`core/rules/LocalTime.kt` implements this as `approximateLocalDateTime`,
used by `PeakOnWeekend`, `PeakInLocalHours`, and (since M3) `core/format`'s
notification-copy time rendering (§10.5) — a notification about a location
200km from the device shouldn't render times in the device's own timezone
assumption. `QuietHours` (§9.1) is the one exception: it's explicitly
device-local, not location-local (§9.1's own framing), so it uses the
device's real `TimeZone.currentSystemDefault()` instead.

## Why this isn't a D-decision reopening

None of D1-D13 specify how "local time" is computed; §9.1 leaves the exact
mechanism to the implementer, the same way §7.2's shower catalog format was
left for verification (ADR 0003). If a real IANA-timezone lookup is wanted
later, swapping `approximateLocalDateTime`'s implementation is a
localized change — the `Cond` shapes and their JSON representation are
unaffected either way.
