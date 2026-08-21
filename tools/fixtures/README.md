# Fixture fetchers (§17.1–17.3b)

Dev-only scripts that capture upstream responses into
`core/src/commonTest/resources/fixtures/`, where §17.3's golden parser tests
read them. Nothing here runs in CI or on a device — CI asserts against the
captures, it does not refresh them.

```sh
tools/fixtures/fetch-swpc.sh        # Kp forecast, full-size OVATION grid, 1-minute nowcast
tools/fixtures/fetch-eonet.sh       # EONET open events, EonetSource's default categories
tools/fixtures/fetch-jpl-sbdb.sh    # the comet query CometSource.discoveryUrl() issues
tools/fixtures/fetch-horizons.py    # SBDB elements + Horizons vectors for the propagator test
```

Needs `curl` (the three shell scripts) and Python 3 with nothing outside the
standard library (the Horizons one).

## What they capture, and why they exist

Each script issues the **same request the corresponding source issues** —
the URLs and query parameters are copied from `AuroraSource`, `KpNowcast`,
`EonetSource` and `CometSource`. Change one of those and change it here, or
the fixture stops describing what the app asks for.

Three of the four capture bytes verbatim. `fetch-horizons.py` cannot: JPL
Horizons serves its `VECTORS` output as a human-formatted table wrapped in a
banner, and reproducing that table's parser inside a test would be testing
the wrong thing. It distills the capture once, into the small JSON the test
reads, and records in the file how it was obtained.

## Refreshing is a review

These are snapshots of live services, so every refresh changes every number
and the diff is large by construction. Read it anyway, looking for the thing
that is not a number: a row encoding that changed, a field that disappeared,
a grid that is no longer 360×181. That is a format drift (§19 R3) — the
failure these fixtures exist to surface, and the one that otherwise shows up
as a source silently returning nothing.

The golden tests are written to survive a refresh: they assert counts,
ranges, ordering and extent, not the particular values in today's capture.
If one fails after a refresh, the upstream shape moved.

`core/src/commonTest/resources/fixtures/README.md` lists every fixture, its
source URL, and which script regenerates it. The GSFC eclipse CSVs are the
one exception with no script — see that file for why.
