# Test fixtures (§17.3)

Real captured responses and reference data for the golden tests. **Never edit
a file here by hand** — the next refresh reverts the edit and the fixture
stops describing anything real.

Refreshing is a review, not a rubber stamp: every number moves, because these
are snapshots of live services. What must not move is the tests' agreement
with them. A *shape* change in the diff — a row encoding, a field that
disappeared, a grid that is no longer 360×181 — is a finding (§19 R3), not
noise.

| File | Source | Regenerate with |
|---|---|---|
| `swpc_planetary_k_index_forecast.json` | `services.swpc.noaa.gov/products/noaa-planetary-k-index-forecast.json` | `tools/fixtures/fetch-swpc.sh` |
| `swpc_ovation_aurora_latest.json` | `services.swpc.noaa.gov/json/ovation_aurora_latest.json` (full 360×181 grid, §17.3) | `tools/fixtures/fetch-swpc.sh` |
| `swpc_planetary_k_index_1m.json` | `services.swpc.noaa.gov/json/planetary_k_index_1m.json` | `tools/fixtures/fetch-swpc.sh` |
| `eonet_events_open.json` | `eonet.gsfc.nasa.gov/api/v3/events` (status=open, days=30, `EonetSource`'s default categories) | `tools/fixtures/fetch-eonet.sh` |
| `jpl_sbdb_comets.json` | `ssd-api.jpl.nasa.gov/sbdb_query.api` — the exact query `CometSource.discoveryUrl()` issues | `tools/fixtures/fetch-jpl-sbdb.sh` |
| `jpl_horizons_comet_vectors.json` | `ssd-api.jpl.nasa.gov/sbdb.api` (elements) + `ssd.jpl.nasa.gov/api/horizons.api` (vectors), distilled | `tools/fixtures/fetch-horizons.py` |
| `gsfc_solar_eclipses_2020_2040.csv` | NASA GSFC *Five Millennium Canon of Solar Eclipses*, [`eclipse.gsfc.nasa.gov/SEcat5/SE2001-2100.html`](https://eclipse.gsfc.nasa.gov/SEcat5/SE2001-2100.html) | transcribed by hand — see below |
| `gsfc_lunar_eclipses_2020_2040.csv` | NASA GSFC *Five Millennium Canon of Lunar Eclipses*, [`eclipse.gsfc.nasa.gov/LEcat5/LE2001-2100.html`](https://eclipse.gsfc.nasa.gov/LEcat5/LE2001-2100.html) | transcribed by hand — see below |
| `gsfc_local_solar_circumstances_named_eclipses.csv` | GSFC per-eclipse local-circumstances pages for the named eclipses | transcribed by hand — see below |

## Why the GSFC rows have no fetcher

They are published as HTML canon tables meant for people, not as an API. A
scraper over someone else's table markup is a worse provenance story than a
recorded URL plus a transcription date: it looks automated, and it breaks
silently the first time the page is restyled. These three files are small,
change only when the covered date range does, and carry their source in the
table above and in the comment header of each file.

## What reads them

`core/src/desktopTest/.../testing/Fixtures.kt`, via the `*FixtureTest`
classes. They are read from `desktopTest` rather than `commonTest` — see
[`docs/adr/0009-fixture-files-and-jvm-only-golden-tests.md`](../../../../../docs/adr/0009-fixture-files-and-jvm-only-golden-tests.md).
