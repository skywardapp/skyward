# Build-time tools (dev only)

Not part of the app; scripts that run at build/dev time, not on-device.

- **Natural Earth GeoJSON → binary converter** (§14.1) — converts the
  1:50m land/coastline vectors into `core`'s compact binary map resource.
  Runs as part of the Gradle build, not at runtime. Lands with the desktop
  event map (M6).
- **Fixture fetchers** (§17.1–17.3b) — capture real HTTP responses (SWPC,
  EONET, JPL SBDB/Horizons) and GSFC eclipse canon rows into
  `core/src/commonTest/resources/fixtures/` for the golden tests. Lands with
  M1 (astronomy golden tests) and M4 (polled-source parser tests).

Nothing here yet — both land with the milestones above, not M0.
