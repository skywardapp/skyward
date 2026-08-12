# Skyward — Design Document

**Location-based reminders for natural & sky events**
Android (F-Droid **and** Google Play) + Linux desktop, shared Kotlin Multiplatform core, local-only.

| | |
|---|---|
| Version | 1.1 (2026-08-12) |
| Status | Ready for implementation handoff |
| Audience | Implementing coding agent (assume no context beyond this document) |
| Working name | "Skyward" — placeholder, rename freely; package `dev.fritze.skyward` used throughout as placeholder |
| Changes in 1.1 | Dual Android distribution (F-Droid + Play): D11–D13, §7.4 rewritten (COBS → JPL), §8.6, §10.2, §10.4, §11, §13, §15, §16, §17, §18, §19 updated. v1 is **monetization-clean but carries no monetization machinery** — see D12/D13 and the separate monetization document. |

---

## Table of contents

1. [Product overview](#1-product-overview)
2. [Decision log (locked)](#2-decision-log-locked)
3. [Glossary](#3-glossary)
4. [System architecture](#4-system-architecture)
5. [Domain model](#5-domain-model)
6. [Event source abstraction](#6-event-source-abstraction)
7. [Source specifications (per phenomenon)](#7-source-specifications)
8. [Visibility models](#8-visibility-models)
9. [Reminder rule engine](#9-reminder-rule-engine)
10. [Scheduling & notification subsystem](#10-scheduling--notification-subsystem)
11. [Persistence](#11-persistence)
12. [Settings sync (export/import)](#12-settings-sync-exportimport)
13. [Android app UI specification](#13-android-app-ui-specification)
14. [Desktop app UI specification](#14-desktop-app-ui-specification)
15. [Project structure, build & packaging](#15-project-structure-build--packaging)
16. [Licensing & attribution matrix](#16-licensing--attribution-matrix)
17. [Testing strategy](#17-testing-strategy)
18. [Implementation milestones](#18-implementation-milestones)
19. [Risks & open questions](#19-risks--open-questions)
20. [Appendices](#20-appendices)

---

## 1. Product overview

### 1.1 Elevator pitch

Skyward tells you, ahead of time, about natural events worth seeing from where you are — or from where you'd be willing to travel. "A total solar eclipse crosses 180 km south of you next August — here's when to leave." "Kp is forecast at 6 tonight; aurora may be visible from your latitude." "Perseids peak Thursday night, new moon, radiant high after 23:00."

### 1.2 Core principles (non-negotiable)

- **P1 — Local-only.** No hosted backend, no accounts, no telemetry, no push infrastructure. The only network traffic is direct HTTPS calls from the device to public data providers (NOAA SWPC, NASA EONET, NASA/JPL). Everything that *can* be computed on-device *is* computed on-device (eclipses, meteor showers, moon events).
- **P2 — Shared core.** All domain logic — event acquisition, visibility math, rule evaluation, persistence, sync — lives in one Kotlin Multiplatform module consumed by both apps. Frontends never reimplement domain logic.
- **P3 — Clean on both stores.** The Android app must build on the F-Droid build server (FOSS-only dependencies, from Maven Central / Google Maven only, no Play Services, no Firebase, no JitPack) **and** pass Google Play policy review (§15.4). Where the two conflict, the difference is confined to a product flavour; `:core` never knows which store it is running in.
- **P6 — Monetization-clean, monetization-free.** v1 ships no billing code, no licence checks, no paid tiers, no ads, no donation links, and no flavour split for revenue. But no v1 decision may *foreclose* monetization later: any dependency, data source, or asset whose licence forbids commercial use is excluded from the design (this is why the comet source is JPL, not COBS — D12). "Clean but empty": the door is left unlocked, and nothing is built behind it.
- **P4 — Honest reminders.** Deterministic events (eclipses, shower peaks) get exact, offline alarms **where the OS grants the exact-alarm permission**, and clearly-labelled approximate ones where it does not. Forecast events (aurora, comets) are best-effort. All three cases are named in the UI rather than blurred together — see the three-class contract in §10.1.
- **P5 — Travel-aware.** Every phenomenon answers not just "visible here?" but "where is the nearest place it *is* visible, and how far is that?" — the user's configured travel radius (e.g. 200 km) decides whether an event is worth a notification.

### 1.3 Non-goals (v1)

- No weather/cloud-cover integration (listed as v2 candidate; see §19).
- No background GPS tracking (saved locations + optional foreground "use my location" only).
- No iOS, Windows, or macOS targets (KMP keeps the door open; do not spend effort on them).
- No LAN sync or any device-to-device protocol (file export/import only).
- No in-app satellite-pass (ISS) predictions in v1.
- **No monetization machinery of any kind** — no Play Billing dependency, no in-app purchases, no paid flavour, no licence/entitlement checks, no supporter unlock, no ads, no donation links in the Play build. v1 is free everywhere. (Per P6 the *design* stays compatible with a later paid product; the *code* contains nothing about it. See the companion monetization document for how a separate paid app would be added later.)
- No observed-magnitude comet data (COBS) — excluded on licence grounds, D12.

### 1.4 The two apps

| | Android | Linux desktop |
|---|---|---|
| Role | Primary. Reminder machine in your pocket. | Secondary. Planning & visualization workstation. |
| UI | Jetpack Compose, phone-first | Compose for Desktop; map, timeline, sky chart, aurora dashboard |
| Notifications | Exact alarms where permitted, WorkManager fallback where not (§10.1), plus WorkManager polling → system notifications | Timer loop while running (+ optional autostart tray) → freedesktop DBus notifications |
| Distribution | **F-Droid and Google Play**, same `applicationId`, same signing key (§15.4), plus GitHub releases APK | Flatpak (primary), `.deb`/`.rpm` via jpackage |
| Flavours | `foss` (F-Droid, GitHub) and `play` (Google Play) — they differ **only** in the exact-alarm permission (§10.2). No other divergence in v1. | n/a |

---

## 2. Decision log (locked)

These were agreed with the product owner on 2026-08-12. Do not reopen them; if implementation reveals a hard blocker, stop and surface it instead of silently deviating.

| # | Decision | Choice | Rationale |
|---|---|---|---|
| D1 | Shared-core technology | **Kotlin Multiplatform** (Kotlin 2.4.x) | One language across core + both UIs; proven F-Droid path (FeedFlow, kitshn); Compose for Desktop stable on Linux |
| D2 | Role of NASA EONET | **Secondary source** | EONET covers natural *hazards* (wildfires, volcanoes, storms…), none of the sky phenomena. It becomes the reference implementation of the HTTP-polling adapter and feeds optional "terrestrial event" categories |
| D3 | v1 phenomena | **Solar & lunar eclipses, aurora, meteor showers, comets & rare sky events** (supermoons, conjunctions) | Full scope selected by owner |
| D4 | Location model | **Saved named locations + optional foreground *coarse* GPS** | No `ACCESS_BACKGROUND_LOCATION`, and no `ACCESS_FINE_LOCATION` either — Play's April 2026 location policy makes coarse the recommended minimum scope, and rise/set/visibility maths is insensitive to a few hundred metres. Desktop and Android share the same model |
| D5 | Cross-device sync | **File export/import** (single versioned JSON) | Zero infrastructure; Syncthing/Nextcloud-friendly |
| D6 | Reminder configuration | **Full rule engine** (user-composable conditions) | Owner explicitly chose maximum flexibility; mitigated by shipping strong defaults (§9.6) and building the engine before the rule-builder UI (§18) |
| D7 | Desktop visualizations | **All four: event map, timeline/calendar, sky chart, live aurora dashboard** | Owner selected all; milestone order in §18 de-risks |
| D8 | App license | **GPL-3.0-or-later** | Required for bundling Stellarium's meteor-shower catalog (GPL-2.0-or-later); F-Droid-friendly (§16) |
| D9 | Eclipse data strategy | **Compute on-device with vendored Astronomy Engine** (MIT, pure Kotlin), GSFC catalog as test oracle only | No network needed, arbitrary date range, one code path for global + local circumstances (§7.1) |
| D10 | Notification transport | Android: `AlarmManager` exact alarms + WorkManager; **no FCM on either store** — it is impossible on F-Droid, and useless on Play because a push transport needs a backend to push *from*, which P1 forbids. Desktop: in-process scheduler + DBus | §10 |
| D11 | Android distribution | **Both F-Droid and Google Play**, one `applicationId`, one signing key held by the developer (generated locally, copy uploaded to Play App Signing via PEPK), F-Droid publishing the developer-signed reproducible build | Single signature ⇒ users can move between stores without losing data; also the most robust position under Android Developer Verification (§15.4, R11) |
| D12 | Comet data source | **JPL SBDB orbital elements + on-device Kepler propagation**; COBS removed entirely | COBS is CC BY-NC-SA (NonCommercial) and its ShareAlike term is GPL-incompatible — it would foreclose monetization (P6) and can never be bundled. JPL is a US Government work with no such limits, and elements-plus-propagation additionally yields sky positions, upgrading §8.6 from a magnitude-only check to a real visibility model. Cost: predicted, not observed, magnitudes (§7.4.4) |
| D13 | Monetization | **None in v1, and no machinery for it** | Owner's decision. Product flavours exist only for the exact-alarm permission difference (§10.2, §15.1); they carry no billing, entitlement, or feature-gating code. A future paid product is designed as a *separate application* and is out of scope for this document |

---

## 3. Glossary

| Term | Meaning |
|---|---|
| **Phenomenon** | A category of natural event: `SOLAR_ECLIPSE`, `LUNAR_ECLIPSE`, `AURORA`, `METEOR_SHOWER`, `COMET`, `MOON_EVENT` (supermoon), `CONJUNCTION`, `TERRESTRIAL` (EONET) |
| **Occurrence** | One concrete instance of a phenomenon with a time (window) and geometry: *the* 2027-08-02 total solar eclipse; *the* 2026 Perseids peak; *an* aurora alert window; *a* specific EONET wildfire |
| **Visibility model** | Per-phenomenon logic answering: is occurrence O observable from location L, at what quality, and if not, where is the nearest point it is observable (§8) |
| **Travel distance** | Great-circle km from a saved location to the nearest point where the occurrence is observable at the required quality |
| **Rule** | A user-configured predicate tree + notification schedule; when a (occurrence, location) pair matches the predicate, notifications are planned (§9) |
| **Source** | An adapter producing occurrences: computed (ephemeris, offline) or polled (HTTP) (§6) |
| **Kp** | Planetary K-index, 0–9, global geomagnetic activity measure; drives aurora visibility |
| **ZHR** | Zenithal Hourly Rate — idealized meteors/hour at zenith radiant, dark sky |
| **Geomagnetic latitude** | Latitude relative to the geomagnetic dipole axis; aurora visibility is organized by it, not geographic latitude |
| **Lead time** | Interval between a notification and the event it announces |

---

## 4. System architecture

### 4.1 Module diagram

```
┌────────────────────────────────────────────────────────────────────┐
│                       :androidApp (Android)                        │
│  Jetpack Compose UI · AlarmManager/WorkManager glue · Android      │
│  notifications · foreground location · SAF file pickers            │
├────────────────────────────────────────────────────────────────────┤
│                       :desktopApp (JVM/Linux)                      │
│  Compose for Desktop UI · map/timeline/skychart/aurora views ·     │
│  in-process scheduler · DBus notifications · tray + autostart      │
├────────────────────────────────────────────────────────────────────┤
│                    :core  (Kotlin Multiplatform)                   │
│                                                                    │
│  model/        Domain types (Occurrence, Location, Rule, …)        │
│  astro/        Vendored Astronomy Engine (astronomy.kt, MIT) +     │
│                thin wrappers (time conversion, search helpers)     │
│  sources/      EventSource SPI + implementations:                  │
│                  EclipseSource, MeteorShowerSource, AuroraSource,  │
│                  CometSource, MoonEventSource, ConjunctionSource,  │
│                  EonetSource                                       │
│  visibility/   Per-phenomenon visibility models + geo math         │
│  rules/        Rule AST, serialization, evaluation                 │
│  planner/      Turns (occurrence × location × rule) into           │
│                PlannedNotification records                         │
│  persistence/  SQLDelight database + repositories                  │
│  sync/         Export/import file format                           │
│  net/          Ktor client config, per-host politeness, caching    │
│  platform/     expect/actual: clock, file paths, locale            │
└────────────────────────────────────────────────────────────────────┘
```

Three Gradle modules only (`:core`, `:androidApp`, `:desktopApp`). Do **not** split `:core` into many modules; package-level separation inside `:core` is enough and keeps the build simple for the F-Droid server. UI code is intentionally **not** shared between the two apps (D1 note: frontends may differ) — only `:core` is shared. If, during implementation, some small presentational helpers (formatting, colors for quality levels) want sharing, put them in `:core/format/` as pure functions.

### 4.2 Dataflow (one cycle)

```
        ┌──────────────┐   refresh()    ┌──────────────────┐
        │ SourceRunner │ ─────────────▶ │  EventSource[n]  │──HTTP──▶ SWPC/JPL/EONET
        │ (per sched.) │ ◀───────────── │  (or computed)   │
        └──────┬───────┘  occurrences   └──────────────────┘
               │ upsert
               ▼
        ┌──────────────┐    for each (occurrence, savedLocation):
        │  OccurrenceDB │──▶ VisibilityModel.evaluate() ──▶ VisibilityResult
        └──────────────┘                                        │
                                                                ▼
        ┌──────────────┐   matches?    ┌────────────────────────────┐
        │  Rule engine │ ◀──────────── │ (occurrence, loc, visres)  │
        └──────┬───────┘               └────────────────────────────┘
               │ yes → plan notifications (dedup against history)
               ▼
        ┌────────────────────┐   platform glue   ┌─────────────────────┐
        │ PlannedNotification │ ────────────────▶ │ AlarmManager / DBus │
        └────────────────────┘                    └─────────────────────┘
```

The **planner** is a pure function: `plan(occurrences, locations, rules, now, alreadySent) -> List<PlannedNotification>`. Platform code only translates `PlannedNotification` into OS alarms/notifications. This keeps everything testable in `commonTest` (§17).

### 4.3 Threading & reactivity

- All core APIs are `suspend` or return `kotlinx.coroutines.flow.Flow`.
- SQLDelight + coroutines-extensions provides `Flow<List<T>>` per query; UIs collect these — no manual refresh plumbing.
- One `CoroutineScope` per app owns the `SourceRunner`; sources never launch their own global coroutines.
- Astronomy computations run on `Dispatchers.Default`; a full 50-year eclipse scan is CPU-bound and must never run on the main thread.

---

## 5. Domain model

All types live in `core/model/`. Everything is `@Serializable` (kotlinx-serialization) and immutable (`data class` / `enum class`). Times are `kotlin.time.Instant` (UTC) unless suffixed `Local`; timezone conversion happens only at the UI/notification edge using `kotlinx-datetime`.

```kotlin
enum class Phenomenon {
    SOLAR_ECLIPSE, LUNAR_ECLIPSE, AURORA, METEOR_SHOWER,
    COMET, MOON_EVENT, CONJUNCTION, TERRESTRIAL
}

@Serializable
data class GeoPoint(val latDeg: Double, val lonDeg: Double) // WGS84, lon in [-180, 180)

@Serializable
data class SavedLocation(
    val id: String,              // UUID v4
    val name: String,            // "Home", "Cabin"
    val point: GeoPoint,
    val isPrimary: Boolean,      // exactly one primary; used for defaults & desktop views
    val createdAt: Instant, val modifiedAt: Instant,   // modifiedAt drives sync merge (§12.3)
)

/** One concrete event instance, source-agnostic. */
@Serializable
data class Occurrence(
    val id: String,              // stable natural key, see §6.4 — NOT a UUID
    val phenomenon: Phenomenon,
    val sourceId: String,        // which EventSource produced it ("eclipse", "swpc", "jpl", "eonet", …)
    val title: String,           // human-readable: "Total solar eclipse", "Perseids 2026 peak"
    val window: TimeWindow,      // earliest..latest relevant instant (see per-source specs)
    val peakTime: Instant?,      // the single most notable instant, if meaningful
    val certainty: Certainty,    // CERTAIN (ephemeris) | FORECAST (aurora, comet brightness)
    val payload: OccurrencePayload, // phenomenon-specific data, sealed — see below
    val fetchedAt: Instant,      // when the source produced/updated this record
    val expiresAt: Instant?,     // forecast occurrences go stale; null for ephemeris events
)

@Serializable data class TimeWindow(val start: Instant, val end: Instant)

enum class Certainty { CERTAIN, FORECAST }

@Serializable
sealed class OccurrencePayload {
    @Serializable data class SolarEclipse(
        val kind: SolarEclipseKind,        // PARTIAL, ANNULAR, TOTAL, HYBRID
        val greatestEclipsePoint: GeoPoint,
        val greatestEclipseTime: Instant,
        // Sampled central-path polyline for TOTAL/ANNULAR/HYBRID, else empty. §7.1.3
        val centralPath: List<PathSample>,
        val obscurationAtGreatest: Double, // 0..1
    ) : OccurrencePayload()

    @Serializable data class PathSample(
        val time: Instant, val point: GeoPoint,
        val pathWidthKm: Double?,          // null if not computed
        val centralDurationSec: Double?,
    )

    @Serializable data class LunarEclipse(
        val kind: LunarEclipseKind,        // PENUMBRAL, PARTIAL, TOTAL
        val penumbralBegin: Instant, val partialBegin: Instant?,
        val totalBegin: Instant?, val totalEnd: Instant?,
        val partialEnd: Instant?, val penumbralEnd: Instant,
    ) : OccurrencePayload()

    @Serializable data class MeteorShower(
        val iauCode: String,               // "PER", "GEM", "QUA"
        val name: String,                  // "Perseids"
        val zhr: Int?,                     // null → variable; see zhrNote
        val zhrNote: String?,              // e.g. "variable, 10–120"
        val radiantRaDeg: Double, val radiantDecDeg: Double, // J2000 at peak, drift applied
        val speedKmS: Double?,
        val parentBody: String?,
        val activityStart: Instant, val activityEnd: Instant, // this year's window
        val moonIlluminationAtPeak: Double, // 0..1, computed at ingest
    ) : OccurrencePayload()

    @Serializable data class Aurora(
        val kpForecast: Double,            // max Kp in the occurrence window
        val forecastKind: AuroraForecastKind, // NOWCAST (OVATION, ~30-90 min) | THREE_DAY (Kp table)
        val issuedAt: Instant,
        // For NOWCAST: OVATION grid is NOT stored in the occurrence; the latest grid
        // lives in source_state (§11) and visibility reads it directly.
    ) : OccurrencePayload()

    @Serializable data class Comet(
        val designation: String,           // "C/2025 K1", "12P"
        val name: String?,                 // "(Pons-Brooks)"
        val elements: CometElements,       // JPL SBDB osculating elements — enables local propagation
        val magParams: CometMagParams,     // M1/K1 (total) from SBDB
        val perihelionDate: Instant,
        // Precomputed at ingest by scanning the horizon window (§7.4.3); all PREDICTED:
        val peakMag: Double, val peakMagDate: Instant,
        val magAtIngest: Double,
    ) : OccurrencePayload()

    /** Heliocentric osculating elements, J2000 ecliptic, as published by JPL SBDB. */
    @Serializable data class CometElements(
        val epoch: Instant,                // osculation epoch
        val eccentricity: Double,          // e
        val perihelionDistanceAu: Double,  // q
        val inclinationDeg: Double,        // i
        val ascendingNodeDeg: Double,      // Omega
        val argPerihelionDeg: Double,      // omega (w)
        val tpPerihelion: Instant,         // Tp — time of perihelion passage
    )

    /** Standard comet photometric parameters: m = M1 + 5·log10(delta) + 2.5·K1·log10(r) */
    @Serializable data class CometMagParams(val m1: Double, val k1: Double)

    @Serializable data class MoonEvent(
        val kind: MoonEventKind,           // SUPERMOON (v1); MICROMOON reserved
        val fullMoonTime: Instant, val perigeeTime: Instant,
        val perigeeDistanceKm: Double,
    ) : OccurrencePayload()

    @Serializable data class Conjunction(
        val body1: String, val body2: String,   // "Venus", "Moon", "Jupiter"
        val minSeparationDeg: Double,
        val timeOfClosest: Instant,
    ) : OccurrencePayload()

    @Serializable data class Terrestrial(   // EONET
        val eonetId: String,
        val categoryId: String,            // "volcanoes", "wildfires", "severeStorms", …
        val categoryTitle: String,
        val latestGeometry: GeoPoint,      // most recent geometry point (or polygon centroid)
        val geometryDate: Instant,
        val magnitudeValue: Double?, val magnitudeUnit: String?,
        val link: String,                  // EONET self link for "open in browser"
        val closed: Boolean,
    ) : OccurrencePayload()
}

enum class SolarEclipseKind { PARTIAL, ANNULAR, TOTAL, HYBRID }
enum class LunarEclipseKind { PENUMBRAL, PARTIAL, TOTAL }
enum class AuroraForecastKind { NOWCAST, THREE_DAY }
enum class MoonEventKind { SUPERMOON, MICROMOON }

/** How precisely a reminder can actually be delivered (§10.1) — a property of the OS, not the data. */
enum class Precision { EXACT, APPROXIMATE }

enum class NotificationStatus { PENDING, REGISTERED, FIRED, CANCELLED, MISSED }

/**
 * One reminder the planner intends to deliver. Mirrors the planned_notification table (§10.4);
 * `id` is the dedup key and is derived, never random.
 */
@Serializable
data class PlannedNotification(
    val id: String,                    // "<occurrenceId>|<anchorEpochSec>|<leadSec|first>" — §10.4
    val occurrenceId: String,
    val ruleId: String,                // first rule that produced it
    val locationId: String,            // best-quality matching location
    val fireAt: Instant,
    val status: NotificationStatus,
    val precision: Precision,          // EXACT until AlarmScheduler reports otherwise
    val title: String, val body: String,   // rendered at PLAN time; see §10.4 for the one thing
                                           // that is deferred to fire time (the precision hedge)
    val createdAt: Instant, val firedAt: Instant?,
)
```

**Design notes for the implementer**

- `Occurrence.payload` is a sealed hierarchy so the compiler forces every visibility model and every UI renderer to handle exactly its phenomenon. Never stringly-type payload data.
- `expiresAt`: aurora NOWCAST occurrences expire ~2 h after `issuedAt`; THREE_DAY expires when the next daily forecast supersedes it; comet records expire 45 days after fetch, outliving the 30-day refresh cycle (§7.4.3); ephemeris events never expire.
- Serialization of `Instant`: use ISO-8601 strings via the stdlib serializer, not epoch millis, in both DB (TEXT) and sync files — human-inspectable and timezone-unambiguous.

---

## 6. Event source abstraction

### 6.1 The SPI

```kotlin
/** Produces occurrences. Implementations are stateless; state lives in the DB. */
interface EventSource {
    val id: String                       // "eclipse", "meteors", "swpc", "jpl", "moon", "conjunctions", "eonet"
    val phenomena: Set<Phenomenon>
    val kind: SourceKind                 // COMPUTED or POLLED

    /**
     * Produce/refresh occurrences.
     * COMPUTED sources derive them from `horizon` (e.g. all eclipses in the next N years).
     * POLLED sources fetch HTTP and map responses; they receive `state` (their persisted
     * key-value blob, e.g. ETags, last OVATION grid) and return an updated copy.
     */
    suspend fun refresh(req: RefreshRequest): RefreshResult

    /** How often refresh() should run. COMPUTED sources return Schedule.OnHorizonChange. */
    fun schedule(settings: SourceSettings): Schedule
}

enum class SourceKind { COMPUTED, POLLED }

data class RefreshRequest(
    val now: Instant,
    val horizon: TimeWindow,             // how far ahead the app plans; default now..now+3 years (settings)
    val locations: List<SavedLocation>,  // some sources tailor queries (EONET bbox) — may be ignored
    val state: Map<String, ByteArray>,   // persisted per-source state (maps to source_state BLOBs, §11);
                                         //   text values stored UTF-8, binary (OVATION gzip) stored raw
    val settings: SourceSettings,
    val derivedThresholds: DerivedThresholds, // precomputed by SourceRunner from enabled rules (see below)
)

/** Per-source user settings: typed wrapper over app_setting `source.<id>.settings_json`. */
@Serializable
data class SourceSettings(
    val enabled: Boolean = true,
    val params: Map<String, String> = emptyMap(),  // source-specific: EONET "categories"="volcanoes,wildfires",
                                                   // meteors "includeMinor"="false", aurora "tier"="auto", …
)

/**
 * Rule-derived query hints, computed by SourceRunner before each run by scanning enabled
 * rules' condition trees (walk the AST, collect the relevant leaf thresholds):
 *  - minKpOfInterest: min over all KpAtLeast values in enabled AURORA rules, minus 1.0 margin (null if none)
 *  - maxCometMag: max over MagnitudeAtMost values in enabled COMET rules, plus 1.0 margin (null if none)
 *  - maxTravelKm: max over ReachableWithin.km across all enabled rules (drives EONET bbox padding)
 */
data class DerivedThresholds(
    val minKpOfInterest: Double?, val maxCometMag: Double?, val maxTravelKm: Double?,
)

data class RefreshResult(
    val occurrences: List<Occurrence>,   // FULL current truth for this source within horizon (see 6.3)
    val newState: Map<String, ByteArray>,
    val nextRefreshHint: Instant?,       // POLLED sources may suggest next poll (e.g. SWPC cadence)
    val diagnostics: SourceDiagnostics,
)

@Serializable
data class SourceDiagnostics(
    val ok: Boolean,
    val httpStatus: Int? = null,
    val message: String? = null,         // parse warnings, error summary — shown in Settings › Sources
    val itemCount: Int = 0,
    val lastSuccessAt: Instant? = null,
)

sealed class Schedule {
    object OnHorizonChange : Schedule()                    // recompute when horizon/locations/settings change
    data class Periodic(val interval: Duration) : Schedule()
    data class Tiered(val active: Duration, val idle: Duration) : Schedule() // aurora: fast when Kp high
}
```

### 6.2 SourceRunner

One class in `core/sources/` orchestrates all sources:

- Holds the registry `List<EventSource>` (constructed once; order irrelevant).
- `suspend fun runDue(now: Instant, force: Set<String> = emptySet())` — for each enabled source whose next-run time (from `source_state.next_run_at`) ≤ now, plus every source whose id is in `force` (used by pull-to-refresh, §13.2): call `refresh()`, upsert occurrences (§6.3), persist `newState` and computed next-run time, then trigger the planner (§9.7).
- Platform glue calls `runDue` from: WorkManager periodic job (Android), timer loop (desktop), app-foreground event (both), pull-to-refresh (both), and settings changes.
- Failures: a failing source never blocks others; wrap each `refresh()` in try/catch, store the error string + timestamp into diagnostics, apply exponential backoff (2× up to 24 h) for repeated failures. Occurrences from previous successful runs remain valid until `expiresAt`.

### 6.3 Upsert semantics

`RefreshResult.occurrences` is the **complete current truth** for that source within the horizon (not a delta). Upsert algorithm:

1. For each returned occurrence: `INSERT OR REPLACE` by `id` — but preserve the row's `first_seen_at` column.
2. Delete rows for this `sourceId` whose ids are absent from the result **and** whose `certainty == FORECAST` (a withdrawn forecast is gone). `CERTAIN` occurrences absent from a result are kept unless outside horizon (protects against a transient source bug mass-deleting eclipse alarms).
3. After upsert, the planner reconciles scheduled notifications (§10.4): occurrences that changed materially get their pending notifications re-planned; deleted occurrences get theirs cancelled. **Material** means `peakTime` moved > 5 min, `kind` changed, or Kp changed ≥ 0.5. Explicitly **not** material: `Comet.magAtIngest` (display-only, changes every refresh by design, §7.4.3) and `fetchedAt`. Getting this list wrong produces notification storms, so it is a test case (§17.4), not a comment.

### 6.4 Occurrence identity (critical for dedup)

Natural keys, deterministic across devices and re-fetches:

| Source | id format | Example |
|---|---|---|
| Eclipse | `se:<yyyymmdd>` / `le:<yyyymmdd>` (UTC date of greatest eclipse) | `se:20270802` |
| Meteor shower | `ms:<IAU>:<year>` | `ms:PER:2026` |
| Aurora nowcast | `au:now:<issued yyyymmddhhmm>` | `au:now:202608121830` |
| Aurora 3-day | `au:3d:<forecast-window-start yyyymmdd>:<slot>` (slot = 3-h window index 0–23 across 3 days) | `au:3d:20260813:5` |
| Comet | `cm:<designation with all non-alphanumeric chars removed>` | `C/2025 K1` → `cm:C2025K1` |
| Supermoon | `sm:<yyyymm>` (month of full moon) | `sm:202611` |
| Conjunction | `cj:<body1>-<body2>:<yyyymmdd>` (bodies alphabetical) | `cj:moon-venus:20261007` |
| EONET | `eo:<EONET id>` | `eo:EONET_1234` |

Notification dedup keys build on these (§10.4), so identical ids across export/import must mean the same real-world event — hence natural keys, never random UUIDs.


---

## 7. Source specifications

Each subsection specifies: data origin, refresh schedule, mapping to `Occurrence`, and implementation caveats. All external facts below were verified against provider documentation in August 2026; re-verify URLs at implementation time and fail gracefully (§6.2) if formats drift.

### 7.1 EclipseSource (`id = "eclipse"`, COMPUTED)

#### 7.1.1 Engine

Vendor the single-file, pure-Kotlin **Astronomy Engine** (`astronomy.kt`, MIT, © 2019–2025 Don Cross, github.com/cosinekitty/astronomy, v2.1.19) into `core/astro/vendored/`. Do **not** pull it from JitPack: JitPack is not an F-Droid-permitted repository, and the published artifact is JVM-only while the source file itself is pure Kotlin and compiles in `commonMain`. Keep the MIT license header intact and note the vendoring in `NOTICE` (§16). Relevant API (names as in the Kotlin source):

- `globalSolarEclipsesAfter(startTime)` / `nextGlobalSolarEclipse` → `GlobalSolarEclipseInfo { kind, peak, distance, latitude, longitude, obscuration }`
- `searchLocalSolarEclipse(startTime, observer)` / `localSolarEclipsesAfter` → `LocalSolarEclipseInfo { kind, obscuration, partialBegin, totalBegin, peak, totalEnd, partialEnd }` (each an `EclipseEvent { time, altitude }`)
- `lunarEclipsesAfter(startTime)` → `LunarEclipseInfo { kind, peak, sdPenum, sdPartial, sdTotal }` (sd* = semi-durations in minutes)
- Accuracy: ±1 arcmin design target vs. NOVAS; more than sufficient (±1 arcmin ≈ ±2 km on the ground).

#### 7.1.2 Refresh

`Schedule.OnHorizonChange`. On first run and whenever horizon/settings change: enumerate all solar and lunar eclipses with `peak` inside the horizon (default 3 years, user-configurable 1–10 in Settings › Planning horizon). Persist results; recompute is idempotent (natural keys §6.4).

#### 7.1.3 Central path sampling (solar, kind ∈ {TOTAL, ANNULAR, HYBRID})

Astronomy Engine exposes global peak location and per-observer circumstances, but not the path polyline. Compute a sampled centerline with the engine as oracle:

```
fun samplePath(eclipse: GlobalSolarEclipseInfo): List<PathSample> {
  // 1. Coarse global scan: 2.5° grid (144 lon × 72 lat lines, ~10k cells, skip |lat|>85).
  //    For each grid point P: run searchLocalSolarEclipse(peak.time - 4h, Observer(P)).
  //    Keep P if result kind is TOTAL or ANNULAR and peak time within ±4 h of global peak
  //    AND sun altitude at local peak > 0.
  //    Cost: ~10k local-eclipse searches; benchmark target < 60 s desktop, < 3 min on
  //    mid-range Android (run once per eclipse, cached; show progress in UI).
  // 2. Order kept points by their local peak TIME → this traces the path west→east.
  // 3. Refine: bucket by 2-minute local-peak-time slots; per bucket, refine the centroid
  //    point with 4 rounds of ±step/2 hill-climbing (step 2.5°→0.15°) maximizing
  //    central duration (totalEnd - totalBegin). The maximum lies on the centerline.
  // 4. Emit one PathSample per bucket: time, refined point, centralDurationSec,
  //    pathWidthKm = null in v1 (acceptable; visibility uses the ±edge search §8.2).
}
```

Numbers (grid step, bucket size) are starting values; tune with the golden tests (§17.2). For PARTIAL eclipses `centralPath = []`.

#### 7.1.4 Mapping

One `Occurrence` per eclipse. `window` = first..last global contact (approximate with peak ± 3 h if contact times are not cheaply available); `peakTime` = greatest eclipse; `certainty = CERTAIN`; `expiresAt = null`.

### 7.2 MeteorShowerSource (`id = "meteors"`, COMPUTED)

#### 7.2.1 Data

Bundle Stellarium's meteor-shower catalog `showers.json` (from the Stellarium repo's MeteorShowers plugin; GPL-2.0-or-later — drives D8) as a `:core` resource. Structure: map keyed by IAU code; per shower `designation`, `activity[]` (entries: `year` = `"generic"` or specific, `zhr` or `variable` range, `start`/`finish`/`peak` as `MM.DD`), `radiantAlpha/Delta` (+ `driftAlpha/Delta`), `speed`, `parentObj`, `pidx`. At build or first run, parse into typed models; log & skip malformed entries.

Curate at ingest: include showers whose generic ZHR ≥ 10 **or** IAU code ∈ {QUA, LYR, ETA, SDA, PER, ORI, LEO, GEM, URS} (the majors, kept even in bad years); everything else is available behind Settings › Meteors › "include minor showers".

#### 7.2.2 Per-year instantiation

For each year Y intersecting the horizon and each curated shower:

1. Prefer an `activity` entry with `year == Y`; else the `generic` entry.
2. Dates `MM.DD` are calendar dates in year Y (activity windows crossing New Year — QUA start in Dec — belong to the year of the *peak*; handle the wrap).
3. Refine peak instant: Stellarium dates are day-precision. Compute the instant within peak-day ± 1 day when the Sun's apparent ecliptic longitude equals the shower's canonical solar longitude **if** the implementer adds λ☉ values from the IAU MDC list (optional enhancement, §19); otherwise use 00:00 UTC of the peak date and treat precision as ±12 h (this is fine: shower peaks are broad).
4. Apply radiant drift: radiant at peak = `radiantAlpha + driftAlpha × daysFromCatalogEpoch` (Stellarium drift is per-day; verify sign against Stellarium plugin source when implementing).
5. Compute `moonIlluminationAtPeak` with Astronomy Engine `illumination(Body.Moon, peakTime).phaseFraction`.

`window` = activityStart..activityEnd; `peakTime` as computed; `certainty = CERTAIN`.

### 7.3 AuroraSource (`id = "swpc"`, POLLED)

#### 7.3.1 Endpoints (NOAA SWPC — US Gov public domain, no key)

| Product | URL | Format | Cadence |
|---|---|---|---|
| 3-day Kp forecast | `https://services.swpc.noaa.gov/products/noaa-planetary-k-index-forecast.json` | **array-of-arrays, first row = header, all values strings**; columns `time_tag, kp, observed("observed"\|"estimated"\|"predicted"), noaa_scale` | 3-hourly values, updated ~daily+ |
| Kp recent (context, dashboard) | `https://services.swpc.noaa.gov/products/noaa-planetary-k-index.json` | same array-of-arrays caveat; `time_tag, Kp, a_running, station_count` | 3 h |
| 1-min estimated Kp | `https://services.swpc.noaa.gov/json/planetary_k_index_1m.json` | array of objects: `time_tag, kp_index, estimated_kp, kp` | 1 min |
| OVATION aurora nowcast | `https://services.swpc.noaa.gov/json/ovation_aurora_latest.json` | object: `"Observation Time"`, `"Forecast Time"`, `"coordinates"`: 360×181 triples `[lon 0–359, lat −90..90, probability 0–100]` (~900 kB) | ~5 min; forecast lead ≈ 30–90 min (solar-wind propagation) |

**Parser warning (repeat because it will bite):** files under `/products/` are `[["header",...],["val",...],...]` with *string* values; files under `/json/` are conventional object arrays. Write one parser per shape, with golden-sample tests from fixture files (§17.3).

#### 7.3.2 Polling schedule (battery-tiered)

`Schedule.Tiered(active = 15.minutes, idle = 3.hours)` — **idle**: only the 3-day forecast is polled. Switch to **active** (poll OVATION + forecast) when `derivedThresholds.minKpOfInterest` ≤ the max predicted Kp in the next 48 h, or the user opens the aurora dashboard. Never poll OVATION in idle: 900 kB × 96/day is rude and pointless. On Android, active-tier work runs via expedited/periodic WorkManager at 15 min (its floor, §10.2); document in-UI that aurora alerts may lag up to ~15 min beyond forecast lead.

#### 7.3.3 Mapping

- **THREE_DAY:** for each future 3-h slot with predicted Kp ≥ `derivedThresholds.minKpOfInterest` (margin already included, §6.1): one occurrence, `id = au:3d:<date>:<slot>`, `window` = the 3-h slot, `kpForecast` = slot value, `expiresAt` = next forecast issue + 6 h. Slots below every threshold produce nothing (keeps DB small).
- **NOWCAST:** one occurrence per OVATION fetch **only if** at least one savedLocation has grid probability ≥ 10 % within 800 km (matching the visibility model's travel-scan radius, §8.4 — a tighter pre-filter would starve travel-based rules; the real thresholding happens in visibility + rules). `window = ForecastTime .. ForecastTime + 1h`, `expiresAt = fetchedAt + 2h`. Persist the full grid (gzipped) into `source_state` under key `ovation_grid`, with `ovation_time` — the visibility model reads it from there.

### 7.4 CometSource (`id = "jpl"`, POLLED)

**Why not COBS (D12).** The obvious source for comet brightness is COBS, which publishes *observed* visual magnitudes. It is licensed CC BY-NC-SA 4.0. Two independent problems disqualify it: (a) the NonCommercial term forecloses any future paid product (P6), and (b) the ShareAlike term is incompatible with GPL-3.0 (D8), so COBS-derived data could never be bundled or adapted — only ever fetched live and displayed untouched. Rather than build a feature on a source that constrains the project's future, v1 uses JPL and accepts predicted magnitudes. §7.4.4 states the accuracy cost honestly.

#### 7.4.1 Discovery query (rare — monthly)

`GET https://ssd-api.jpl.nasa.gov/sbdb_query.api` with:

```
?sb-kind=c                                  # comets only
&fields=full_name,pdes,name,epoch,e,q,i,om,w,tp,M1,K1,M2,K2
&sb-cdata={"AND":["q|LT|4.5","M1|LT|14"]}   # plausible naked-eye/binocular candidates only
&full-prec=1
```

Returns JSON `{signature, count, fields:[...], data:[[...],[...]]}` — a header/rows shape, like SWPC's `/products/` files but with the field names in a separate array. Typical result: a few hundred rows, ~100 kB. Rows missing `M1` or `K1` are skipped with a diagnostic (many comets have no fitted total-magnitude parameters). **Unit conversion, easy to get silently wrong:** SBDB returns `epoch` and `tp` as **Julian dates (TDB)**; the parser must convert both to `Instant` (`JD 2440587.5` = Unix epoch; 86400 s/day). A missed conversion here shows up as a wholesale ephemeris offset rather than an error, so §17.3b's Horizons comparison is what catches it. `Schedule.Periodic(30.days)` — osculating elements change slowly and JPL refits on a similar cadence. Public domain (US Government work); no key, no documented rate limit; send a descriptive `User-Agent`.

#### 7.4.2 On-device propagation (`core/astro/kepler.kt`)

Everything after the monthly fetch is offline arithmetic — no per-comet network calls, which is what makes this fit P1 better than any observed-magnitude feed could.

**Use the universal-variable (Stumpff) formulation, not per-conic branches.** The obvious design — Newton-Raphson on Kepler's equation for ellipses, Barker's equation for parabolas, the hyperbolic analogue above e = 1 — has a nasty property for exactly this application: most bright comets sit in the near-parabolic band (e ≈ 0.98–1.02), which is where every branch is at its numerically worst and where the handovers introduce discontinuities. The universal-variable formulation handles all conic types in one code path with no branching and no discontinuity, which also makes §17.3b's tests meaningful rather than branch-dependent.

```kotlin
// core/astro/vec3.kt
data class Vec3(val x: Double, val y: Double, val z: Double) {
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    fun length() = sqrt(x*x + y*y + z*z)
}

// core/astro/kepler.kt
const val GAUSS_K = 0.01720209895          // rad/day, AU^(3/2)

/** Stumpff functions c2(z), c3(z); use the series expansion for |z| < 1e-6. */
private fun stumpff(z: Double): Pair<Double, Double> = when {
    z > 1e-6  -> { val s = sqrt(z);  Pair((1 - cos(s)) / z, (s - sin(s)) / (s*s*s)) }
    z < -1e-6 -> { val s = sqrt(-z); Pair((1 - cosh(s)) / z, (sinh(s) - s) / (s*s*s)) }
    else      -> Pair(0.5 - z/24.0, 1.0/6.0 - z/120.0)     // series, avoids 0/0
}

/** Heliocentric position, J2000 ecliptic, AU. Valid for any eccentricity. */
fun heliocentricPosition(el: CometElements, t: Instant): Vec3 {
    // 1. dt = days since perihelion passage (t - el.tpPerihelion).
    // 2. Solve the universal Kepler equation for the universal anomaly x by
    //    Newton-Raphson with z = alpha*x^2, where alpha = (1 - e) / q  (alpha is
    //    1/a for ellipses, 0 for parabolas, negative for hyperbolas — no branch).
    //    Seed x = GAUSS_K * dt * abs(alpha).pow(0.5) for alpha != 0, else the
    //    Barker seed. Iterate to |f| < 1e-11, max 30 iterations; if it fails to
    //    converge, drop the comet with a diagnostic rather than emitting garbage.
    // 3. Recover true anomaly nu and heliocentric distance r from x, z and the
    //    Stumpff functions; then rotate the orbital-plane vector (r·cos nu, r·sin nu, 0)
    //    by argPerihelion (w), inclination (i), ascendingNode (Omega) — the standard
    //    Rz(-Omega)·Rx(-i)·Rz(-w) sequence — into J2000 ecliptic coordinates.
}

fun apparentMagnitude(el: CometElements, mp: CometMagParams, t: Instant): Double {
    val cometHelio = heliocentricPosition(el, t)
    val earthHelio = /* Astronomy Engine helioVector(Body.Earth, t) → ecliptic AU as Vec3 */
    val r = cometHelio.length()                       // comet–Sun distance, AU
    val delta = (cometHelio - earthHelio).length()    // comet–Earth distance, AU
    return mp.m1 + 5 * log10(delta) + 2.5 * mp.k1 * log10(r)
}
// RA/Dec for the sky chart and the altitude gate (§8.6) come from the same geocentric
// vector (cometHelio - earthHelio), rotated ecliptic→equatorial by the J2000 obliquity.
```

Two-body propagation from osculating elements ignores planetary perturbations and non-gravitational (outgassing) forces. Over the ≤ 12 months this source looks ahead, positional error stays well under a degree for most comets — irrelevant at "which comets are up and roughly how bright" precision. Refetching elements monthly bounds the drift.

#### 7.4.3 Occurrence construction

**Anchor the scan on the orbit, not on `now`.** Scanning forward from `now` would make `peakMagDate` slide forward by ~30 days at every monthly refresh once perihelion has passed — §6.3 would score that as a material change, the planner would re-plan, and the "7 days before peak" lead would fire again and again for a comet that already faded. Instead:

- **Scan range:** `min(now, tp − 9 months) .. max(now, tp + 9 months)`, where `tp = elements.tpPerihelion`, in 1-day steps computing `apparentMagnitude`. This window always contains the true brightness maximum and does not move when `now` moves.
- **Ingest filter:** emit an occurrence only if `min(magnitude) ≤ ingestFloor`, where `ingestFloor = max(derivedThresholds.maxCometMag ?: 6.0, 6.0)` (the field is nullable — null when no comet rule is enabled, §6.1) — the floor keeps every comet the §8.6 visibility model could possibly rate MARGINAL, so the model is never starved by the source. (`derivedThresholds.maxCometMag` already carries its +1.0 margin from §6.1; do not add another.)
- `peakMag` / `peakMagDate` = the scan minimum, refined to the hour by golden-section. Because the scan range is orbit-anchored, these are **stable across refreshes** — assert this in tests.
- `magAtIngest` = magnitude at `fetchedAt`. This is the only field that legitimately changes each refresh, and §6.3's material-change test must ignore it (it is display data, not scheduling data).
- `peakTime = peakMagDate`; `window` = the contiguous interval around the peak where magnitude ≤ `ingestFloor`, clamped to the scan range (a comet is "interesting" for as long as it is plausibly visible, typically weeks).
- `certainty = FORECAST` — these are model predictions, and the UI must not imply otherwise.
- `expiresAt = fetchedAt + 45.days` (outlives the 30-day refresh cycle with margin).

**Planner note (applies to all sources, surfaced here because comets are where it bites):** a lead whose computed `fire_at` is already in the past *at plan time* must be dropped, not queued. §10.4's "fire immediately if still within `occurrence.window`" rule applies only to notifications that were genuinely scheduled and missed — never to leads that were in the past the moment they were computed.

#### 7.4.4 Honest limitations (surface these in the UI)

Predicted magnitudes derive from `M1`/`K1` fitted to past observations. They are least reliable exactly where the app is most interesting: **dynamically new comets** (first perihelion passage — often fitted optimistically and then underperform) and **outbursts** (17P/Holmes-class brightening by many magnitudes in hours, entirely absent from a two-body model). Comet detail must therefore show "predicted magnitude" with the model date, and link out to a live source for a reality check. Do **not** phrase comet notifications with the confidence used for eclipses. A permitted observed-magnitude source is the natural v1.1 upgrade (R12) and the `EventSource` seam is where it plugs in.

### 7.5 MoonEventSource (`id = "moon"`, COMPUTED) — supermoons

For each lunation in horizon: `searchMoonPhase(180°)` → full moon instant; nearest perigee via `searchLunarApsis`/`nextLunarApsis`. **Supermoon definition (state it in the UI; there is no official one):** full moon within ±24 h of perigee AND perigee distance < 360 000 km. `window` = fullMoon ± 12 h, `peakTime` = full moon, `certainty = CERTAIN`.

### 7.6 ConjunctionSource (`id = "conjunctions"`, COMPUTED)

v1 scope: Moon–planet and planet–planet close approaches among {Venus, Mars, Jupiter, Saturn, Mercury}. Algorithm: for each pair, scan the horizon in 6 h steps computing geocentric angular separation (Astronomy Engine `equator` + spherical law of cosines helper `angleBetween`); where a local minimum < threshold (Moon–planet: 2.0°, planet–planet: 1.0°), refine by golden-section to the minute. Only emit if elongation from Sun > 15° (else unobservable). Mapping: `peakTime = timeOfClosest`, `window = timeOfClosest ± 12.hours`, `certainty = CERTAIN`, `expiresAt = null`.

### 7.7 EonetSource (`id = "eonet"`, POLLED)

- `GET https://eonet.gsfc.nasa.gov/api/v3/events?status=open&days=30` (+ optionally `category=` from user's enabled categories; no API key). Default enabled categories: `volcanoes`, `severeStorms`, `wildfires` — everything else off by default (Settings › Sources › EONET).
- Response mapping: per event → `Terrestrial` payload; `latestGeometry` = last element of `geometry[]` (Point directly; Polygon → arithmetic centroid of ring vertices); `window` = first geometry date .. (closed date or fetchedAt + 7 d); `certainty = FORECAST`; `expiresAt = fetchedAt + 3.days`.
- If ≥ 2 saved locations are within 2 000 km of each other, pass a `bbox` covering all saved locations + max travel radius to cut payload; **EONET bbox order is nonstandard: `bbox=minLon,maxLat,maxLon,minLat`**.
- Poll `Schedule.Periodic(6.hours)`.
- EONET's own disclaimer says positions/dates are approximations for general information; surface EONET events as "informational" (no ±minute precision claims in UI).


---

## 8. Visibility models

### 8.1 Common contract

```kotlin
interface VisibilityModel {
    val phenomenon: Phenomenon
    /** Pure function; must not do I/O except reading pre-fetched grids passed in ctx. */
    fun evaluate(occ: Occurrence, loc: SavedLocation, ctx: VisibilityContext): VisibilityResult
}

data class VisibilityContext(
    val now: Instant,
    val ovationGrid: OvationGrid?,     // decoded from source_state, may be null
)

/** Decoded OVATION nowcast grid (§7.3.1): 360×181, 1°×1°, geographic coordinates. */
class OvationGrid(
    val observationTime: Instant, val forecastTime: Instant,
    private val prob: ByteArray,       // 360*181 entries, index = (lon0to359 * 181) + (lat + 90); values 0..100
) { fun probabilityAt(lonDeg0to359: Int, latDeg: Int): Int = prob[(lonDeg0to359 * 181) + (latDeg + 90)].toInt() }

/**
 * Phenomenon-specific local circumstances for UI + notification copy. One subtype per
 * phenomenon; fields are exactly what §10.5 templates and detail screens (§13.3) consume.
 */
@Serializable
sealed class LocalDetails {
    @Serializable data class SolarEclipseLocal(
        val partialBegin: Instant?, val peak: Instant, val partialEnd: Instant?,
        val maxObscuration: Double, val sunAltAtPeakDeg: Double, val localKind: SolarEclipseKind?,
    ) : LocalDetails()
    @Serializable data class LunarEclipseLocal(
        val visiblePhaseStart: Instant, val visiblePhaseEnd: Instant, val moonAltAtMidDeg: Double,
        val umbralFractionVisible: Double,
    ) : LocalDetails()
    @Serializable data class MeteorLocal(
        val bestViewingStart: Instant?, val bestViewingEnd: Instant?,   // null when no dark radiant-up window
        val maxRadiantAltDeg: Double, val moonIllumination: Double, val moonUpDuringBest: Boolean,
    ) : LocalDetails()
    @Serializable data class AuroraLocal(
        val geomagneticLatDeg: Double, val kpNeeded: Double, val ovationProbability: Int?,   // null for THREE_DAY
        val darknessStart: Instant?, val darknessEnd: Instant?,          // null = no astronomical night (midsummer)
    ) : LocalDetails()
    @Serializable data class CometLocal(
        val predictedMag: Double, val elementEpoch: Instant,   // epoch feeds the "as of" caveat, §7.4.4
        val maxAltDeg: Double, val maxAltTime: Instant?,
        val bestViewingStart: Instant?, val bestViewingEnd: Instant?,
    ) : LocalDetails()
    @Serializable data class GenericLocal(val note: String) : LocalDetails()  // supermoon, conjunction, EONET
}
```

`Anchor.BEST_VIEWING` (§9.1) resolves to `MeteorLocal.bestViewingStart` or `CometLocal.bestViewingStart` — the two phenomena that have a meaningful "go outside now" window. For any other payload, or a null window, it falls back to `PEAK`.

```kotlin

data class VisibilityResult(
    val visibleAtLocation: Boolean,     // at required baseline quality (per model, below)
    val quality: Quality,               // NONE, MARGINAL, GOOD, EXCELLENT — model-specific rubric
    val localDetails: LocalDetails?,    // times/altitudes at the location (for UI + notifications)
    // Travel guidance — null when visibleAtLocation, or when travel cannot help (meteor
    // shower with radiant never up is a timing problem, not a distance problem):
    val nearestVisiblePoint: GeoPoint?,
    val travelDistanceKm: Double?,      // great-circle from loc to nearestVisiblePoint
    val travelBearingDeg: Double?,      // initial bearing, for "≈180 km SSE"
    val qualityAtNearestPoint: Quality?,
)

enum class Quality { NONE, MARGINAL, GOOD, EXCELLENT }
```

Geodesy helpers in `core/visibility/geo.kt`: haversine distance, initial bearing, destination point (all spherical, R = 6371.0088 km — spherical error ≤ 0.5 % is irrelevant at this app's precision; do not pull in a geodesy library).

Every model documents its Quality rubric; keep rubrics stable — rule conditions reference them.

### 8.2 Solar eclipse

- **At location:** `searchLocalSolarEclipse(window.start - 1h, Observer(loc))`; confirm the found eclipse matches this occurrence (peak within window). Visible if an eclipse occurs with sun altitude > 0 at local peak. Quality: EXCELLENT = local kind TOTAL/ANNULAR; GOOD = partial with obscuration ≥ 0.8; MARGINAL = obscuration ≥ 0.2; NONE below / sun down.
- **Travel target = the central path** (people travel for totality, not for a bigger partial): nearest sample in `payload.centralPath` by haversine; then refine ±1 sample-interval by ternary search over interpolated path points. `qualityAtNearestPoint = EXCELLENT`. For PARTIAL-only eclipses, travel guidance: nearest point where obscuration ≥ 0.8, found by hill-climbing from `loc` toward `greatestEclipsePoint` (8 steps of bisection along the geodesic, evaluating obscuration via local search); if none, null.
- `localDetails`: partial begin/peak/end local times, max obscuration, sun altitude at peak — feed notification body text (§10.5).

### 8.3 Lunar eclipse

Visible wherever the Moon is up during the interesting phase — no path math. At `loc`: compute Moon altitude (Astronomy Engine `equator`+`horizon`) at the interesting-phase start — `totalBegin ?: partialBegin ?: penumbralBegin` — and at the corresponding phase end. Quality: EXCELLENT = entire umbral phase with Moon up; GOOD = ≥ 50 % of umbral phase; MARGINAL = any umbral visibility or penumbral-only eclipse with Moon up; NONE otherwise. Travel: for NONE, nearest visible point = walk the geodesic toward the sub-lunar point at mid-eclipse (up to 6 bisection steps until Moon altitude > 5°); often thousands of km — the rules' distance cap will filter it out naturally.

### 8.4 Aurora

Two regimes:

- **THREE_DAY (planning):** dipole geomagnetic latitude of `loc`:
  `sin λgm = sin λ sin λp + cos λ cos λp cos(φ − φp)` with geomagnetic north pole (IGRF-14/WMM2025, epoch 2025) at **λp = 80.85° N, φp = −72.76° E**. Hard-code with a named constant + comment; drift is ~0.1°/yr — revisit each IGRF epoch (§19).
  Visibility boundary (SWPC rule of thumb): aurora visible on the horizon down to geomagnetic latitude **λvis ≈ 66° − 2° × Kp**. Then: visible if `|λgm| ≥ λvis`. Quality: EXCELLENT = `|λgm| ≥ λvis + 4` (overhead likely); GOOD = `≥ λvis + 1.5`; MARGINAL = `≥ λvis − 1` (horizon glow, needs dark northern horizon); else NONE.
  Travel: needed Δλgm = `λvis − |λgm|` (if > 0). Nearest visible point ≈ move Δλgm° along the great circle toward the geomagnetic pole; `travelDistanceKm ≈ Δλgm × 111.2`. (Approximation is fine — this is trip-planning guidance, not navigation.)
  Additional gate in `localDetails`: aurora needs darkness — compute whether the slot overlaps local astronomical night (sun < −12°); if not (midsummer high latitudes), cap quality at MARGINAL and say why.
- **NOWCAST (alerting):** look up `ctx.ovationGrid` at the four grid cells around `loc` (bilinear on the 1°×1° grid; grid lon is 0–359 — convert). `probability ≥ 25` → visibleAtLocation. Quality: EXCELLENT ≥ 70, GOOD ≥ 50, MARGINAL ≥ 25. Travel: scan grid cells within a fixed 800 km model radius for probability ≥ 50, pick nearest (the rule's `ReachableWithin.km` then caps it during evaluation — the model never sees rule parameters). Darkness gate as above.

### 8.5 Meteor shower

No distance concept — the sky is the venue. `visibleAtLocation` = during the night containing `peakTime` (local sun < −12°), the radiant reaches altitude ≥ 20° (compute radiant alt from RA/Dec via `horizon()` hourly across that night; take max). Travel fields = null always.
Quality rubric (peak night): start from ZHR class (ZHR ≥ 60 → base EXCELLENT; ≥ 20 → GOOD; ≥ 10 → MARGINAL; < 10 or `zhr == null`/"variable" → MARGINAL — variable showers can outburst, let rules filter by `ZhrAtLeast` which treats null ZHR as 0), then subtract one level if `moonIlluminationAtPeak > 0.6` and the Moon is above horizon during the radiant-up dark hours, and subtract one level if max radiant altitude < 35°. Floor at MARGINAL if radiant ≥ 20° at all; NONE if the radiant never clears 20° in darkness (e.g. far-southern observer for PER).
`localDetails`: best viewing interval (dark + radiant ≥ 20° + Moon-adjusted), max radiant altitude, moon phase %.

### 8.6 Comet

Because D12's source supplies orbital elements, the comet model is a real visibility model, not just a magnitude filter. Evaluate over the night containing `now` (or containing `occ.peakTime` when the occurrence is still in the future):

1. **Magnitude gate:** `m = apparentMagnitude(elements, magParams, t)` at the evaluated time. Baseline `visibleAtLocation` requires `m ≤ 6.0` (fixed model constant; rules apply their own `MagnitudeAtMost` on top, §9.1).
2. **Altitude-in-darkness gate:** propagate to RA/Dec (§7.4.2), convert to altitude at `loc` hourly across the night; require max altitude ≥ 15° while the Sun is below −12°. A bright comet permanently below the horizon or lost in twilight is not visible, and saying so is the whole point of having positions.
3. **Quality:** start from magnitude (EXCELLENT ≤ 2, GOOD ≤ 4, MARGINAL ≤ 6), then drop one level if max altitude < 25°, and one more if the Moon is above the horizon and > 60 % illuminated during the comet's above-horizon dark window. Floor MARGINAL while both gates pass; NONE if either fails.
4. **Travel fields:** null. Comet visibility is a timing-and-latitude matter, not a "drive 200 km" matter; for a far-southern-declination comet the honest answer is "not from here this apparition", which `localDetails` states in words.

`localDetails` = `CometLocal` (§8.1), carrying predicted magnitude, the elements' epoch, max altitude and when it occurs, and the best viewing window. The §7.4.4 caveat is a fixed string rendered by the formatter, not stored per-occurrence.

**Cache note:** this model's result depends on `now` (which night is being evaluated), unlike the eclipse and shower models whose inputs are fixed. Its `visibility_cache` entries must therefore include the local calendar date in `data_version` (§11), or a comet's verdict would stay frozen for the whole 30-day refresh cycle.

### 8.7 Supermoon / conjunction

Supermoon: visible if Moon up at any point of the night of full moon (nearly always) — quality EXCELLENT if perigee distance < 357 000 km else GOOD. Conjunction: visible if both bodies simultaneously above 10° altitude with sun < −6° at some time within ±12 h of closest approach at `loc`; quality by separation (EXCELLENT < 0.5°, GOOD < 1°, else MARGINAL). Travel null for both.

### 8.8 Terrestrial (EONET)

The model never sees rule parameters (§8.1), so it reports raw facts and lets `ReachableWithin` threshold them: `visibleAtLocation = false` always, `quality = GOOD` (fixed — EONET has no meaningful quality), `travelDistanceKm` = haversine(loc, latestGeometry), `nearestVisiblePoint` = the event location itself, `qualityAtNearestPoint = GOOD`. A rule like `ReachableWithin(300 km, GOOD)` then matches iff the event is within 300 km.

---

## 9. Reminder rule engine

Owner chose the **full rule engine** (D6). Design: a small, typed, JSON-serializable predicate AST + a notification schedule per rule. No free-text DSL, no user-visible syntax — the UI is a structured builder (§13.4); the engine is the contract.

### 9.1 Rule shape

```kotlin
@Serializable
data class Rule(
    val id: String,                    // UUID
    val name: String,                  // "Total eclipses within 500 km"
    val enabled: Boolean,
    val phenomena: Set<Phenomenon>,    // which occurrence types this rule sees
    val locationIds: List<String>?,    // null = all saved locations
    val condition: Cond,               // predicate tree, below
    val schedule: NotifySchedule,
    val hidden: Boolean = false,       // true for system-generated rules (one-off reminders, per-event mutes,
                                       //   §13.3) — excluded from the Rules list UI, included in evaluation & sync
    val createdAt: Instant, val modifiedAt: Instant,
)

@Serializable
sealed class Cond {
    @Serializable data class And(val all: List<Cond>) : Cond()
    @Serializable data class Or(val any: List<Cond>) : Cond()
    @Serializable data class Not(val inner: Cond) : Cond()

    // Visibility-derived (computed per (occurrence, location) before evaluation):
    @Serializable data class VisibleAtLocation(val minQuality: Quality = Quality.MARGINAL) : Cond()
    @Serializable data class ReachableWithin(val km: Double, val minQualityThere: Quality = Quality.GOOD) : Cond()
       // true if visibleAtLocation at minQualityThere, OR travelDistanceKm ≤ km with qualityAtNearestPoint ≥ minQualityThere

    // Phenomenon-parameter conditions (evaluate against payload; false if payload lacks the field):
    @Serializable data class KpAtLeast(val kp: Double) : Cond()
    @Serializable data class ZhrAtLeast(val zhr: Int) : Cond()
    @Serializable data class MagnitudeAtMost(val mag: Double) : Cond()
       // comets: tests payload.peakMag (the best the comet is predicted to get), NOT magAtIngest —
       // a rule about "bright comets" is a rule about the apparition, not about today
    @Serializable data class EclipseKindIn(val kinds: Set<SolarEclipseKind>) : Cond()
    @Serializable data class LunarKindIn(val kinds: Set<LunarEclipseKind>) : Cond()
    @Serializable data class MoonIlluminationAtMost(val fraction: Double) : Cond()
    @Serializable data class EonetCategoryIn(val categoryIds: Set<String>) : Cond()
    @Serializable data class CertaintyIs(val certainty: Certainty) : Cond()
    @Serializable data class AuroraKindIs(val kind: AuroraForecastKind) : Cond()   // NOWCAST vs THREE_DAY
    @Serializable data class OccurrenceIdIs(val id: String) : Cond()
       // targets one specific occurrence — powers "add one-off extra reminder" and, under Not(), per-event mute (§13.3)

    // Temporal/contextual:
    @Serializable data class PeakInDaysAhead(val maxDays: Int) : Cond()
    @Serializable data class PeakOnWeekend(val includeFridayNight: Boolean = true) : Cond()
       // "weekend" = local Fri 18:00–Mon 06:00 at the location's timezone when includeFridayNight
    @Serializable data class PeakInLocalHours(val fromHour: Int, val toHour: Int) : Cond() // wraps midnight
}

@Serializable
data class NotifySchedule(
    val leads: List<Duration>,         // e.g. [30.days, 7.days, 1.days, 2.hours] before anchor
    val anchor: Anchor,                // PEAK or WINDOW_START or BEST_VIEWING (from localDetails, falls back to PEAK)
    val notifyOnFirstSeen: Boolean,    // fire as soon as occurrence first matches (aurora nowcast, comets, EONET)
    val quietHours: QuietHours?,       // suppress+defer to end of quiet window (null = none); FORECAST-only events
                                       //   whose window would end before quiet ends are dropped, not deferred
)
enum class Anchor { PEAK, WINDOW_START, BEST_VIEWING }
@Serializable data class QuietHours(val fromHour: Int, val toHour: Int)  // device-local
```

Serialize `Cond` with kotlinx-serialization class discriminator `"type"` — sync files and DB store this JSON; it must stay backward-compatible (only add types, never rename; unknown types on import → rule imported as disabled with a warning, §12.3).

### 9.2 Evaluation semantics

For each (occurrence, savedLocation ∈ rule.locationIds, rule with occurrence.phenomenon ∈ rule.phenomena):

1. Run the phenomenon's `VisibilityModel.evaluate` → `visres` (cache per (occId, locId, sourceDataVersion)).
2. Evaluate `rule.condition` bottom-up with `(occ, loc, visres, now)`. Missing-field conditions (e.g. `KpAtLeast` on an eclipse) are `false`, never errors. Rules with empty `phenomena` match nothing.
3. If true → emit `Match(ruleId, occId, locId, visres)` to the planner (§10.4).

### 9.3 Multiple rules / multiple locations

Matches dedup at notification level, not match level: notification identity key = `(occurrenceId, anchorTime, lead)` — if two rules or two nearby saved locations produce the same key, one notification, listing the first matching rule; body shows the best (highest-quality) location. This prevents double-buzzing for "Home" and "Office" 10 km apart.

### 9.4 What is deliberately NOT in the AST

Weather/cloud cover (no source in v1), light-pollution (no bundled atlas in v1 — §19), free-text scripting. The AST is closed and versioned; adding a `Cond` subtype is the designed extension path.

### 9.5 Rule limits

Cap: 100 rules, tree depth ≤ 8, ≤ 50 nodes per rule — enforced at save time. Keeps evaluation trivially fast (worst case ~8 phenomena × 10 locations × 100 rules × 50 nodes ≈ 4 M cheap ops, well under a second; visibility results are cached).

### 9.6 Shipped default rules (created on first launch, editable/deletable)

| Name | Phenomena | Condition | Schedule |
|---|---|---|---|
| "Total & annular eclipses — worth a trip" | SOLAR_ECLIPSE | `And[EclipseKindIn{TOTAL,ANNULAR,HYBRID}, ReachableWithin(500 km, EXCELLENT)]` | leads [180d, 30d, 7d, 1d, 2h], anchor PEAK |
| "Partial eclipse overhead" | SOLAR_ECLIPSE | `VisibleAtLocation(GOOD)` | leads [7d, 1d, 2h] |
| "Lunar eclipse visible from home" | LUNAR_ECLIPSE | `VisibleAtLocation(GOOD)` | leads [7d, 1d, 1h] |
| "Major meteor showers, decent conditions" | METEOR_SHOWER | `And[ZhrAtLeast(20), VisibleAtLocation(GOOD)]` | leads [3d, 6h], anchor BEST_VIEWING |
| "Aurora heads-up (planning)" | AURORA | `And[AuroraKindIs(THREE_DAY), KpAtLeast(5), ReachableWithin(200 km, MARGINAL)]` | leads [12h], anchor WINDOW_START, notifyOnFirstSeen |
| "Aurora NOW" | AURORA | `And[AuroraKindIs(NOWCAST), VisibleAtLocation(MARGINAL)]` | notifyOnFirstSeen only, quietHours 00–06 off by default |
| "Bright comet, actually visible" | COMET | `And[MagnitudeAtMost(4.0), VisibleAtLocation(MARGINAL)]` | notifyOnFirstSeen + leads [7d] anchored on peak |
| "Supermoon" | MOON_EVENT | `VisibleAtLocation(GOOD)` | leads [1d] |
| "Close conjunctions" *(shipped disabled)* | CONJUNCTION | `VisibleAtLocation(GOOD)` | leads [1d] |
| "Volcano within reach" *(shipped disabled)* | TERRESTRIAL | `And[EonetCategoryIn{volcanoes}, ReachableWithin(300 km, GOOD)]` | notifyOnFirstSeen |

### 9.7 Re-planning triggers

Planner runs after: any source upsert (§6.3), rule/location/settings edit, sync import, boot (Android), app start (desktop), and daily at 04:00 local as a safety net.


---

## 10. Scheduling & notification subsystem

### 10.1 Honesty contract (P4)

**Three** classes of reminder, and the UI/notification copy must distinguish them:

- **Exact** (eclipses, showers, supermoons, conjunctions — `certainty = CERTAIN`, exact-alarm permission held): scheduled as OS-level exact alarms years ahead; survive offline; never silently dropped.
- **Approximate** (same events, but the exact-alarm permission is *denied* — possible on any Android 14+ device and the normal state for the `play` flavour until the user grants it, §10.2): scheduled through WorkManager instead; may drift by tens of minutes and slip in Doze. `PlannedNotification.precision = APPROXIMATE`, and the copy says so ("around 21:40").
- **Best-effort** (aurora, comets, EONET — `FORECAST`): depend on polling; on Android the floor is ~15 min lag and Doze can add more. Settings › Notifications shows a plain-language explanation; aurora notifications carry the forecast issue time ("based on 18:30 UTC forecast"); comet notifications carry the predicted-magnitude caveat (§7.4.4).

The three classes are orthogonal: `certainty` is a property of the *data*, `precision` a property of the *delivery*. A lunar eclipse is always CERTAIN, but its reminder is APPROXIMATE on a device that withheld the permission.

### 10.2 Android implementation

**Permissions — shared manifest** (`androidApp/src/main/AndroidManifest.xml`):

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />       <!-- runtime, API 33+ -->
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />   <!-- optional feature -->
<!-- NO ACCESS_FINE_LOCATION (D4), NO background location, NO foreground services,
     NO REQUEST_IGNORE_BATTERY_OPTIMIZATIONS (Play prohibits it for this app category
     and setExactAndAllowWhileIdle already pierces Doze). -->
```

**Permissions — flavour-specific.** This is the *only* difference between the two flavours in v1:

```xml
<!-- androidApp/src/fossMain/AndroidManifest.xml  (F-Droid, GitHub) -->
<uses-permission android:name="android.permission.USE_EXACT_ALARM" />          <!-- API 33+, auto-granted -->
<uses-permission android:name="android.permission.SCHEDULE_EXACT_ALARM"
                 android:maxSdkVersion="32" />

<!-- androidApp/src/playMain/AndroidManifest.xml  (Google Play) -->
<uses-permission android:name="android.permission.SCHEDULE_EXACT_ALARM" />
<!-- USE_EXACT_ALARM deliberately absent. Play restricts it to "alarm or timer" and
     "calendar apps that shows event notifications" and requires a Console declaration.
     Skyward arguably qualifies as the latter, but reviewers read it narrowly and the
     downside of a wrong bet is a blocked release. SCHEDULE_EXACT_ALARM is user-grantable
     and needs no declaration; the app asks for it in onboarding and degrades if refused. -->
```

- **Exact path:** for every `PlannedNotification` within the next 14 days, register `AlarmManager.setExactAndAllowWhileIdle(RTC_WAKEUP, …)` to a manifest-declared `BroadcastReceiver` (`NotificationAlarmReceiver`) which reads the payload from DB by id and posts the notification. Alarms beyond 14 days are *not* registered with the OS (OS limits + reboot fragility); the daily WorkManager job tops up the 14-day window. On `BOOT_COMPLETED` (and `MY_PACKAGE_REPLACED`): re-register the window.
- **Approximate path (new, required — build it in M3, not later):** when `AlarmManager.canScheduleExactAlarms()` is false, schedule a one-off `WorkManager` job with `setInitialDelay(fireAt - now)` instead, mark the row `precision = APPROXIMATE`, and render the copy variant. Both paths live behind one interface so the planner never branches on it:

  ```kotlin
  interface AlarmScheduler {                      // implemented in :androidApp
      fun canScheduleExact(): Boolean
      fun schedule(n: PlannedNotification): Precision   // returns what it actually achieved
      fun cancel(id: String)
  }
  ```
  This interface is also the test seam (§17.5). It is not Play-specific: any Android 14+ user can revoke the permission, so the `foss` flavour needs the same fallback.
- **Polling path:** one periodic WorkManager unique work (`skyward-refresh`), interval = min over enabled POLLED sources' current tier, floor 15 min, constraint NETWORK_CONNECTED. The worker calls `SourceRunner.runDue()` then `Planner.replan()`; new immediate matches (`notifyOnFirstSeen`) post directly from the worker.
- **Doze reality:** `setExactAndAllowWhileIdle` fires in Doze (rate-limited ~1/9 min per app — fine, our alarms are sparse). Periodic work in deep Doze may slip to maintenance windows → aurora lag; do not chase this with `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` in v1 (revisit if field reports demand it, §19).
- **Channels:** one notification channel per phenomenon (user can silence meteor showers but keep eclipses loud) + one "app diagnostics" channel. Aurora NOWCAST channel defaults to high importance.
- **Runtime permission flow:** first launch asks POST_NOTIFICATIONS with rationale. Then, whenever `canScheduleExactAlarms()` is false (always the initial state for the `play` flavour on API 31+; only API 31–32 for `foss`), show a dismissible card explaining what precision is lost and linking to `ACTION_REQUEST_SCHEDULE_EXACT_ALARM`. Listen for `ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED`: revocation cancels all exact alarms → immediately re-plan everything onto the approximate path; a later grant re-plans back. Never nag more than once per app version.
- **Location prominent disclosure (required by Play, harmless on F-Droid):** before the first `ACCESS_COARSE_LOCATION` runtime prompt, show a full-screen disclosure stating what is accessed (approximate location), why (to compute visibility from where you are), and that it is used **on-device only and never transmitted**. The permission is entirely optional — manual location entry is the default path and the app must be fully usable without ever granting it.

### 10.3 Desktop implementation

- In-process scheduler: a coroutine loop (`delay` until next due item, recompute on DB change via Flow). Runs while the app runs; with "Background mode" enabled (Settings), closing the window hides to tray instead of exiting.
- Tray: use **ComposeNativeTray** (`io.github.kdroidfilter:composenativetray`, Maven Central, StatusNotifierItem/AppIndicator over DBus) — Compose's built-in AWT `Tray` is broken on GNOME. Autostart: write XDG `~/.config/autostart/skyward.desktop` when enabled; inside Flatpak use the `org.freedesktop.portal.Background` portal instead (detect via `FLATPAK_ID` env).
- Notifications: freedesktop `org.freedesktop.Notifications` over DBus. Implementation order: try `com.sshtools:two-slices` (Maven Central); if it fights the Flatpak sandbox, fall back to spawning `notify-send` (present in `org.freedesktop.Platform`). Clicking a notification raises the window on the relevant detail view (DBus action if supported; else best effort).
- Missed-while-closed events: on startup, list matches whose anchor passed while the app was closed in an "While you were away" panel instead of firing stale notifications.

### 10.4 PlannedNotification & dedup

```sql
-- §11 schema; semantics here
planned_notification(
  id TEXT PRIMARY KEY,               -- dedup key (§9.3), one of:
                                     --   "<occurrenceId>|<anchorEpochSec>|<leadSec>"  (scheduled lead)
                                     --   "<occurrenceId>|<anchorEpochSec>|first"      (notifyOnFirstSeen)
                                     -- For first-seen rows the anchor is the occurrence's peakTime, or
                                     -- window.start when peakTime is null (aurora nowcast, EONET). One
                                     -- first-seen notification per occurrence, ever — that is the point
                                     -- of putting the anchor, not `now`, in the key.
  occurrence_id TEXT NOT NULL,
  rule_id TEXT NOT NULL,             -- first rule that produced it
  location_id TEXT NOT NULL,         -- best-quality matching location
  fire_at TEXT NOT NULL,             -- ISO instant
  status TEXT NOT NULL,              -- PENDING | REGISTERED (has OS alarm) | FIRED | CANCELLED | MISSED
  precision TEXT NOT NULL DEFAULT 'EXACT',  -- EXACT | APPROXIMATE (§10.1). Planner inserts the
                                     --   default; AlarmScheduler.schedule() overwrites it with what it
                                     --   actually achieved, which is why the hedge is applied at fire
                                     --   time rather than baked into `body` at plan time.
  title TEXT NOT NULL, body TEXT NOT NULL,   -- rendered at PLAN time and re-rendered whenever the
                                     --   occurrence changes materially (§6.3). The ONE exception is the
                                     --   APPROXIMATE hedge from §10.5: it depends on `precision`, which
                                     --   is only known once the row is registered, so the formatter
                                     --   applies it at fire time on top of the stored body.
  created_at TEXT NOT NULL, fired_at TEXT
)
```

Planner reconciliation: compute desired set of `(id → fire_at)`; insert new as PENDING; cancel OS alarms + mark CANCELLED for rows no longer desired; `FIRED` rows are permanent history (auto-pruned after 180 days). A notification whose `fire_at` passed while unregistered (device off) fires immediately on next planner run if still within `occurrence.window`, else marked MISSED (visible in history).

### 10.5 Notification copy (render in `core/format/`, unit-tested)

Template per phenomenon; examples of tone & required data:

- Eclipse, far lead: **"Total solar eclipse — Aug 2, 2027"** / "Path of totality passes 180 km SSE of Home. At Home: 92 % partial at 10:48. Nearest totality: 2 min 10 s near Valencia."
- Eclipse, 2 h lead: **"Eclipse today"** / "First contact at Home 09:32, max 10:48 (92 %). Totality 180 km SSE — leave by 08:00 to be safe." (travel-time line only if travelDistance ≤ rule km; compute leave-by as fire_at of the 2 h lead — no routing engine, phrase as rough guidance.)
- Meteor shower: **"Perseids peak tonight"** / "Best 23:40–04:10 at Home. Radiant up to 62°, Moon 8 % — dark skies. Expect up to ~1 meteor/min under clear skies." (Translate ZHR honestly: "up to ~N/hr in perfect conditions".)
- Comet: **"Comet C/2027 A1 near its best"** / "Predicted magnitude 4.2 — binocular target. Highest at 38° around 04:10, dark sky until 05:20. Prediction from JPL elements of 2027-02-01; comets often deviate." (Never omit the deviation clause, §7.4.4.)
- Any notification whose row has `precision = APPROXIMATE` renders times with a hedge ("around 21:40") and appends once, on the first such notification only: "Times are approximate — enable exact alarms in Settings for precise reminders."
- Aurora nowcast: **"Aurora possible NOW at Home"** / "OVATION 62 % overhead probability (18:55 UTC forecast). Look north after full darkness (~21:40)."
- All bodies end with the location name they refer to. No emoji in v1 copy.

---

## 11. Persistence

SQLDelight 2.3.x (`app.cash.sqldelight`), database name `skyward.db`. Drivers: `android-driver` (Android, minSdk ≥ 23 required by 2.3.2) / `sqlite-driver` JdbcSqliteDriver + Xerial (desktop, DB at `$XDG_DATA_HOME/skyward/skyward.db`, fallback `~/.local/share/skyward/`). One `.sq` file per table; `verifyMigrations = true` in Gradle config.

```sql
CREATE TABLE saved_location (
  id TEXT PRIMARY KEY, name TEXT NOT NULL,
  lat_deg REAL NOT NULL, lon_deg REAL NOT NULL,
  is_primary INTEGER NOT NULL DEFAULT 0,
  created_at TEXT NOT NULL, modified_at TEXT NOT NULL
);

CREATE TABLE occurrence (
  id TEXT PRIMARY KEY, phenomenon TEXT NOT NULL, source_id TEXT NOT NULL,
  title TEXT NOT NULL,
  window_start TEXT NOT NULL, window_end TEXT NOT NULL, peak_time TEXT,
  certainty TEXT NOT NULL,
  payload_json TEXT NOT NULL,          -- kotlinx-serialization of OccurrencePayload
  fetched_at TEXT NOT NULL, expires_at TEXT, first_seen_at TEXT NOT NULL
);
CREATE INDEX occ_by_window ON occurrence(window_start, window_end);
CREATE INDEX occ_by_source ON occurrence(source_id);

CREATE TABLE rule (
  id TEXT PRIMARY KEY, name TEXT NOT NULL, enabled INTEGER NOT NULL,
  phenomena_json TEXT NOT NULL, location_ids_json TEXT,   -- null = all
  condition_json TEXT NOT NULL, schedule_json TEXT NOT NULL,
  hidden INTEGER NOT NULL DEFAULT 0,
  created_at TEXT NOT NULL, modified_at TEXT NOT NULL
);

CREATE TABLE planned_notification ( /* as §10.4 */ );

CREATE TABLE source_state (
  source_id TEXT NOT NULL, key TEXT NOT NULL, value BLOB NOT NULL,
  updated_at TEXT NOT NULL, PRIMARY KEY (source_id, key)
);  -- holds: next_run_at, backoff_count, etags, ovation_grid (gzip), diagnostics_json

CREATE TABLE visibility_cache (
  occurrence_id TEXT NOT NULL, location_id TEXT NOT NULL,
  data_version TEXT NOT NULL,          -- hash of (occurrence.fetchedAt, relevant source_state keys,
                                       --   AND the local calendar date when the model's result depends
                                       --   on `now` — comets, §8.6. Date-independent models omit it.)
  result_json TEXT NOT NULL, computed_at TEXT NOT NULL,
  PRIMARY KEY (occurrence_id, location_id)
);

CREATE TABLE app_setting ( key TEXT PRIMARY KEY, value TEXT NOT NULL );
-- keys: horizon_years, units (metric/imperial), source.<id>.enabled, source.<id>.settings_json,
--       aurora_tier_state, onboarding_done, background_mode (desktop), theme, schema_version
```

Repositories (`core/persistence/`): `LocationRepo`, `OccurrenceRepo`, `RuleRepo`, `NotificationRepo`, `SourceStateRepo`, `SettingsRepo` — thin, returning Flows; no business logic in repos.

---

## 12. Settings sync (export/import)

### 12.1 What syncs

Locations, rules, app settings, per-source settings, notification *history keys* (ids of FIRED planned_notifications, so a second device doesn't re-notify past events). **Not synced:** occurrence cache (recomputable/refetchable), OVATION grids, diagnostics.

### 12.2 File format

Single JSON file `skyward-settings-<yyyyMMdd-HHmm>.json` (MIME `application/json`):

```json
{
  "format": "skyward-sync",
  "formatVersion": 1,
  "exportedAt": "2026-08-12T18:00:00Z",
  "appVersion": "1.0.0",
  "locations": [ /* SavedLocation[] */ ],
  "rules": [ /* Rule[] (condition as tagged JSON, §9.1) */ ],
  "settings": { "horizon_years": "3", "...": "..." },
  "firedNotificationIds": [ "se:20260812|1786556760|7200", "..." ]
}
```

### 12.3 Import semantics (merge, never wipe)

- Match by `id`. Locations and rules both carry `modifiedAt`: incoming wins iff its `modifiedAt` is newer; otherwise keep local. Never delete local records absent from the file (a file is a snapshot, not a mirror). Offer "Replace everything" as an explicit destructive secondary action with confirmation.
- Unknown `formatVersion` → refuse with message. Unknown `Cond` type inside a rule (older app) → import that rule `enabled=false` + warning list shown post-import.
- `firedNotificationIds` union-merge into history (as FIRED, synthetic).
- After import: full re-plan (§9.7). Export/import UI: Android SAF create/open document; desktop file dialog, default dir `$XDG_DOCUMENTS_DIR`. This format is Syncthing-friendly by being a plain file the user drops anywhere.
- **Forward-compatibility note (P6):** this file is also the migration path between *separate applications* — e.g. from this free app to a hypothetical future paid one, or between the F-Droid and Play builds should their signatures ever diverge (§15.4). Keep the format application-agnostic: no field may encode which app or store produced it beyond the informational `appVersion`. That costs nothing now and preserves the option later.

---

## 13. Android app UI specification

Material 3, Jetpack Compose, dynamic color; single-Activity, Navigation-Compose; dark theme default-follows-system. Phone-first; make screens scale acceptably on tablets via simple max-width containers (no dedicated tablet layouts in v1).

### 13.1 Navigation map

```
BottomBar: [Upcoming] [Map*] [Rules] [Settings]        *Map tab is v1.1 on Android; hide behind flag
Upcoming ─▶ EventDetail ─▶ (rule matches, visibility per location, share)
Rules ─▶ RuleEditor (structured condition builder)
Settings ─▶ Locations ─▶ LocationEditor (map-less: search box + lat/lon + "use current location")
        ─▶ Sources (per-source toggle, status/diagnostics, last fetch)
        ─▶ Notifications (channels shortcut, quiet hours, honesty explainer §10.1,
                          exact-alarm permission state + enable button)
        ─▶ Sync (export / import)
        ─▶ About (licenses & attributions §16, privacy policy link, source-code link)
Onboarding (first run): welcome → add first location (location prominent disclosure
        before any coarse-location prompt, §10.2) → notification permission →
        exact-alarm explainer + optional grant → default rules preview
```

**About screen is compliance-load-bearing, not decoration.** It must carry: (a) a link to the hosted privacy policy — mandatory for Play even though the app collects nothing; (b) a link to the public source repository at the tag matching this `versionCode` — this is how GPL §6(d) is satisfied for store-distributed binaries; (c) the §16 attribution list; (d) app version + flavour + build type, for bug reports.

### 13.2 Upcoming (home) screen

- Chronological card list of occurrences within horizon that match ≥ 1 enabled rule OR are "notable anyway" (EXCELLENT quality at any saved location) — toggle chip row: [Matched] [All] [per-phenomenon chips].
- Card: phenomenon icon, title, countdown ("in 3 weeks"), location line ("Visible from Home — GOOD" or "180 km SSE of Home"), quality badge color-coded (EXCELLENT green / GOOD teal / MARGINAL amber).
- Aurora nowcast state (if active) pinned as a banner card with live Kp.
- Pull-to-refresh triggers `SourceRunner.runDue(now, force = <ids of enabled POLLED sources>)`.

### 13.3 EventDetail

Per-phenomenon detail blocks (times table at each saved location, visibility verdicts, travel guidance with bearing arrow, path mini-map for eclipses [static canvas drawing of centralPath + location markers — no tile map needed], moon conditions for showers, OVATION probability + link "open dashboard on desktop" hint for aurora, EONET external link).

**Comet block (compliance-relevant, per §7.4.4 — not optional):** predicted magnitude labelled as *predicted*, the element epoch it derives from ("from JPL elements of 2027-02-01"), max altitude and best viewing window from `CometLocal`, and the standing caveat that comets frequently deviate from prediction. Include one external link for a reality check — point it at the object's **JPL SBDB page** (`https://ssd.jpl.nasa.gov/tools/sbdb_lookup.html#/?sstr=<designation>`), which keeps the app consistent with its own data source. Do not link COBS here: a link is harmless in itself, but it invites the "just parse that page too" shortcut that D12 exists to prevent. Actions: **mute this event** — creates a hidden *suppressor* rule (`hidden=true`, `condition=OccurrenceIdIs(id)`, `leads=[]`, `notifyOnFirstSeen=false`); the planner's final step drops every planned notification whose occurrence matches any enabled suppressor (empty-schedule hidden rule). **Add one-off extra reminder** — hidden rule `OccurrenceIdIs(id)` with the user-picked lead. **Share as text.**

### 13.4 RuleEditor (the structured condition builder)

- Rule = name + phenomena multi-select + locations select + condition builder + schedule editor.
- Condition builder UI = nested groups: a group is AND/OR toggle + list of condition rows + "add condition" / "add group" (NOT rendered as a per-row "invert" toggle). Condition row = type dropdown (filtered to types meaningful for the selected phenomena) + typed inputs (sliders for km/Kp/ZHR/mag with sensible ranges, quality dropdowns).
- Live preview panel: "matches N upcoming events" — run the engine on the fly against current DB occurrences (debounce 500 ms). For COMPUTED phenomena the preview additionally enumerates the *past* 2 years on demand (sources are pure functions of time — call them with a past horizon into a temporary in-memory list, never into the DB; cache per session). Polled phenomena (aurora/comet/EONET) preview against current+future data only, with the caption "forecast-based — past matches can't be shown".
- Guardrails: §9.5 limits; deleting a group prompts if non-empty.

### 13.5 Widgets / complications

Out of v1. (Glance widget "next event" is a fine v1.1.)

---

## 14. Desktop app UI specification

Compose for Desktop, single window ~1280×800 default, left nav rail: [Overview] [Map] [Timeline] [Sky chart] [Aurora] [Rules] [Settings]. Rules/Settings/EventDetail reuse the same core view-models as Android with desktop layouts (two-pane where natural). Overview = Upcoming list + mini aurora status + next-eclipse hero card.

### 14.1 Event map

- **Rendering: custom Compose Canvas, equirectangular projection** (lon→x, lat→y linear), pan/zoom via transformable state (clamp zoom 1×–8×). Base layer: Natural Earth 1:50m land + coastline polygons (public domain), converted at build time from GeoJSON to a compact binary float-array resource (script in `tools/`; do the GeoJSON→binary conversion in Gradle, not at runtime).
- Layers (toggleable): eclipse central paths within horizon (polyline + date labels, click → detail); saved locations (pins + travel-radius circles per rule km, drawn as geodesic-correct ellipses — at this projection just approximate with lat-scaled circle); EONET events (category icons); aurora OVATION heat overlay (current grid, alpha-blended cells ≥ 10 %, geographic grid drawn directly — trivially aligned with equirectangular).
- No OSM tiles in v1 ⇒ no tile-licensing burden, fully offline. (If street-level zoom is ever wanted: MapComposeMP + proper tile provider, explicitly out of v1. MapLibre-Compose's desktop target was ~15 % complete as of v0.13.0 — do not adopt.)

### 14.2 Timeline / calendar

- Horizontal time axis (now → horizon end, log-ish zoom: next 60 days get half the width), one lane per phenomenon; occurrence = marker/segment colored by best quality across saved locations; hover tooltip = card summary; click = detail. Month/year grid lines; "today" cursor; filter chips shared with Upcoming.

### 14.3 Sky chart

- Stereographic projection of the local sky (azimuth/altitude) for a selected saved location + time (slider spanning the selected night, defaulting to next astronomical darkness).
- Draw: horizon circle with cardinal labels, altitude rings (30°/60°), Sun/Moon (with phase glyph) and planets (Astronomy Engine `equator`→`horizon` positions), meteor-shower radiants active that night (crosshair + IAU code + expected-rate annotation), eclipse sun/moon position at eclipse times, and comet positions from the Kepler propagator (§7.4.2) for any comet occurrence active that night.
- No star catalog in v1 (keeps scope sane); background gradient by sun altitude (day/twilight/night). Add BSC-lite (~300 brightest stars, public domain) only if trivially easy — decide at implementation, flag in review.

### 14.4 Aurora dashboard

- Row 1: current estimated Kp (1-min product) as gauge; 3-day forecast as 24×3h bar strip (color by Kp class, G-scale labels); "based on forecast issued …" caption.
- Row 2: OVATION polar view — north-polar azimuthal plot of the grid ≥ 45° N (and a south view toggle), user locations overlaid; probability colorbar.
- Row 3: per saved location verdict cards — dipole magnetic latitude, "visible if Kp ≥ N" (inverse of §8.4 formula: `Kp_needed = (66 − |λgm|)/2`), current margin, darkness window tonight.
- Refresh: dashboard-open forces active polling tier (§7.3.2).

---

## 15. Project structure, build & packaging

### 15.1 Repository layout

```
skyward/
├── gradle/libs.versions.toml          # single source of truth for versions
├── settings.gradle.kts                # :core, :androidApp, :desktopApp
├── core/
│   ├── build.gradle.kts               # kotlin("multiplatform"), targets: androidTarget(), jvm("desktop")
│   └── src/{commonMain,commonTest,androidMain,desktopMain}/kotlin/...
│   └── src/commonMain/resources/      # showers.json, natural-earth.bin
├── androidApp/                        # com.android.application, compose
│   └── src/{main,fossMain,playMain,test,androidTest}/
│        # fossMain/ and playMain/ contain ONE file each: AndroidManifest.xml (§10.2).
│        # No Kotlin sources are flavour-specific in v1. If that ever stops being true,
│        # it is a signal to re-read D13 before adding the second file.
├── desktopApp/                        # kotlin("jvm") + org.jetbrains.compose
├── tools/                             # NE GeoJSON converter, fixture fetchers (dev only)
├── fastlane/metadata/android/         # store listing text/screenshots — F-Droid reads this
│                                      #   directly; reuse the same copy for the Play listing
├── flatpak/                           # org.example.Skyward.yml manifest + .desktop + metainfo.xml
└── docs/                              # this document, ADRs for any deviations
```

Flavour declaration (`androidApp/build.gradle.kts`) — deliberately minimal:

```kotlin
flavorDimensions += "store"
productFlavors {
    create("foss") { dimension = "store" }   // F-Droid + GitHub releases
    create("play") { dimension = "store" }   // Google Play
}
// Same applicationId, same versionCode, same signing config for both (§15.4).
// No BuildConfig fields, no dependency differences, no source-set code in v1 (D13).
```

### 15.2 Pinned versions (verified current stable, 2026-08; update deliberately, in lockstep)

| Component | Version | Coordinates / notes |
|---|---|---|
| Kotlin | 2.4.10 | `org.jetbrains.kotlin.multiplatform` |
| Compose Multiplatform | 1.11.1 | plugins `org.jetbrains.compose` + `org.jetbrains.kotlin.plugin.compose` (desktop) |
| Jetpack Compose (Android) | via Compose BOM current at impl time | androidApp only |
| kotlinx-coroutines | 1.11.0 | `-core`, `-android`, `-swing` (desktop Main dispatcher — required) |
| kotlinx-serialization | 1.11.0 | `-json` |
| kotlinx-datetime | 0.8.0 | note: `Instant`/`Clock` now live in stdlib `kotlin.time` |
| Ktor | 3.5.2 | client `-core`, `-okhttp` (Android), `-cio` (desktop), `-content-negotiation`, `ktor-serialization-kotlinx-json` |
| SQLDelight | 2.3.2 | plugin `app.cash.sqldelight`; `android-driver`, `sqlite-driver`, `coroutines-extensions` |
| WorkManager | 2.10.x | `androidx.work:work-runtime-ktx` |
| Astronomy Engine | 2.1.19 **vendored source file** | `core/astro/vendored/astronomy.kt`, MIT header retained |
| ComposeNativeTray | 1.1.x | `io.github.kdroidfilter:composenativetray` (desktop only) |
| two-slices | current | `com.sshtools:two-slices` (desktop only; replaceable by notify-send exec) |

Android: `compileSdk 36`, **`targetSdk 36`** (Play requires API 36 for new apps and updates from 2026-08-31 — not optional, and trivial for a new app), **`minSdk 26`** (Android 8.0 — covers notification channels & `setExactAndAllowWhileIdle`; SQLDelight needs ≥ 23).

### 15.3 Core build must stay pure

`:core` `commonMain` may depend ONLY on: kotlinx-{coroutines, serialization, datetime}, Ktor client core, SQLDelight runtime, okio (if needed for gzip). No Compose, no AndroidX in `commonMain`. `androidMain`/`desktopMain` hold drivers and `expect/actual` (file paths, engine choice).

### 15.4 Android distribution — F-Droid *and* Google Play (D11)

**Signing is the load-bearing decision. Get it right before the first public release; it cannot be undone.**

1. Generate the app signing key **locally** and keep it. Upload a *copy* to Play App Signing via the **PEPK** tool rather than letting Google generate the key — Google's own guidance endorses this precisely so the same key can be used for other stores.
2. Make the build **reproducible** so F-Droid publishes the *developer-signed* APK instead of an F-Droid-signed one. Then both stores ship binaries with the same signature and users can move between them without uninstalling or losing data. (Without this, Android refuses cross-store updates — `INSTALL_FAILED_UPDATE_INCOMPATIBLE` — and the two audiences are permanently separated.)
   - Pin the JDK via Gradle toolchains (Java 17), pin AGP/Kotlin/Compose exactly in the version catalog, no build timestamps in resources, watch R8 and resource-shrinker nondeterminism, verify with `diffoscope` between two clean builds before submitting.
   - This is promoted from "nice to have" (v1.0 of this document) to a **requirement**: it is what makes dual distribution coherent.
   - Caveat to expect and not be alarmed by: Google re-signs the split APKs it generates from the AAB, so the artifact a Play user receives is not byte-identical to the F-Droid APK. Reproducibility is asserted between *your* build and F-Droid's rebuild; the signing *identity* is what carries across.
3. Same `applicationId` on both stores (no registry conflict — `org.tasks` does this today). Same `versionCode`/`versionName` for a given release.

**F-Droid side**

- All dependencies from **Maven Central + Google Maven only** (F-Droid's allowlist; JetBrains' dev repo and JitPack are not allowed — stable CMP artifacts are on Maven Central, and Astronomy Engine is vendored precisely for this reason).
- No proprietary services, no Play Services, no billing dependency (D13 means there is nothing to strip). All endpoints are government/open data, so the `NonFreeNet`-adjacent scanners pass.
- Build the **`fossRelease`** variant. Provide `fastlane/metadata/android/en-US/` (title, short/full description, changelogs, screenshots) — F-Droid reads it from the repo.
- Process reality: initial inclusion is an RFP issue plus a merge request to `fdroiddata`, reviewed by volunteers — **budget weeks, not days**. Publishing after merge is 24–48 h, and subsequent version bumps are picked up automatically via `UpdateCheckMode`.

**Play side**

- Ship the **`playRelease`** variant as an **AAB** (mandatory for new apps).
- Console obligations, all of which are work rather than difficulty: Data Safety form (declare no collection — accurate here, and keep it accurate), IARC content rating questionnaire, target-audience declaration (not child-targeted, to stay out of Families policy), and a **hosted privacy policy** at a stable public non-PDF URL, linked both in Console and in-app (§13.1). GitHub Pages is acceptable.
- Do **not** file the `USE_EXACT_ALARM` declaration; the `play` flavour does not request it (§10.2).
- Account friction to plan for: $25 one-time fee; identity verification with a government photo ID plus proof of address; and — for a personal account created after 2023-11-13 — a **closed test with 12 testers opted in continuously for 14 days** before production access is granted. An organisation account skips the tester gate but requires a D-U-N-S number (free, up to ~28 days) and publishes more contact detail. Start this early; it is a calendar dependency, not an engineering one.
- Privacy cost to accept knowingly: Play publishes the developer's legal name and country (and, under EU trader-status rules, likely address and phone). This has no F-Droid equivalent and cannot be undone after publication.

**Android Developer Verification (both stores, hard deadline)**

Registration binds *package name + signing key*, and unregistered apps become advanced-flow-only on certified devices — enforcement starts 2026-09-30 in Brazil/Indonesia/Singapore/Thailand and expands globally through 2027. Apps signed via Play App Signing are auto-claimed. Holding one developer-owned key across both stores (step 1 above) means one identity to register and no divergence. See R11.

- App id: final reverse-DNS decided by owner before first publication (placeholder `dev.fritze.skyward`) — immutable on both stores afterwards. Versioning: `versionName = semver`, `versionCode = monotonic int`, shared across flavours.

### 15.5 Desktop packaging

- `compose.desktop { application { … } }` with `TargetFormat.Deb`, `TargetFormat.Rpm` (jpackage; JDK 17+; no AppImage support in the Compose plugin — do not attempt).
- **Flatpak (primary):** build `createReleaseDistributable` (jlinked self-contained tree), package that tree with flatpak-builder on `org.freedesktop.Platform` (network-isolated Flathub builds can't run Gradle — repackage the prebuilt tree; this is the FeedFlow-proven pattern). Include `metainfo.xml` (AppStream) + `.desktop` file. Request minimal sandbox: `--share=network`, `--socket=wayland --socket=fallback-x11`, `--talk-name=org.freedesktop.Notifications`, `--talk-name=org.kde.StatusNotifierWatcher`, background portal for autostart.
- ProGuard release minification: enabled, keep rules for kotlinx-serialization + reflection-free config.


---

## 16. Licensing & attribution matrix

App license: **GPL-3.0-or-later** (D8). `LICENSE` = GPLv3; `NOTICE` file enumerates the rows below; About screen renders them.

**Every row below must be commercial-use-clean (P6).** A dependency or dataset that forbids commercial use does not enter this table, regardless of how convenient it is — that rule is what removed COBS (D12). Before adding any new data source, check the licence against this bar first and the technical fit second.

Note on GPL and the stores: distributing a GPL app on Google Play is unproblematic — the Apple/GPL conflict of 2010–2011 came from iTunes' five-device install cap, and Play's Developer Distribution Agreement has no equivalent restriction (it explicitly grants unlimited reinstalls). GPL-3.0 apps ship on Play today (AntennaPod, AnkiDroid, Tasks.org, OsmAnd). The obligation this creates is §6(d): make Corresponding Source available from the same place — in practice a public repo tagged to match each released `versionCode`, linked from the store listing and the About screen (§13.1).

| Component / data | License | Bundled? | Obligations |
|---|---|---|---|
| Astronomy Engine `astronomy.kt` (Don Cross) | MIT | Yes (vendored source) | Keep copyright + license header in file; list in NOTICE/About |
| Stellarium `showers.json` | GPL-2.0-or-later | Yes (resource) | App must be GPL-compatible (it is, GPLv3+); attribute "Meteor shower data from the Stellarium project" |
| Natural Earth 1:50m | Public domain | Yes (converted binary) | Courtesy credit "Made with Natural Earth" |
| NOAA SWPC data (Kp, OVATION) | US Gov public domain | Runtime fetch | Courtesy credit; no restriction |
| NASA EONET metadata | US Gov / NASA open | Runtime fetch | Credit "Event data: NASA EONET"; surface their "general information purposes" disclaimer in About |
| NASA JPL Horizons ephemerides | US Gov public domain | Test fixtures only (§17.3b) | Courtesy credit in test sources + NOTICE |
| NASA GSFC eclipse canon (Espenak & Meeus) | Free with acknowledgment | Test fixtures only | Acknowledge in test sources + NOTICE: "Eclipse predictions courtesy of Fred Espenak and Jean Meeus, NASA/GSFC" |
| JPL SBDB comet elements & magnitude parameters | US Gov public domain | Runtime fetch (monthly) | Courtesy credit "Comet orbital data: NASA/JPL Small-Body Database"; send a descriptive `User-Agent` |
| ~~COBS comet data~~ | ~~CC BY-NC-SA 4.0~~ | **EXCLUDED (D12)** | NonCommercial forecloses monetization (P6) and ShareAlike is GPL-incompatible. Do not reintroduce, not even as an "offline fallback" or a quick fix for §7.4.4's accuracy limits. If observed magnitudes become necessary, obtain written permission first or find a permissively licensed source (R12) |
| All Maven dependencies | Apache-2.0/MIT/BSD (verify at lock time) | Yes | Standard notice aggregation (Gradle license report plugin) |

Trademark care: do not use NASA/NOAA logos; text attribution only.

---

## 17. Testing strategy

All domain tests live in `:core` `commonTest` and run on both JVM and Android (unit) — CI runs `./gradlew check` on every commit (GitHub Actions; also compiles desktop packaging weekly).

### 17.1 Astronomy validation (golden tests)

Fixture: GSFC Five Millennium Canon rows for all solar eclipses 2020–2040 (ASCII catalog → checked-in CSV extract, acknowledgment per §16). Assert per eclipse: kind matches; greatest-eclipse time within ±2 min; greatest-eclipse lat/lon within ±30 km. Same for lunar eclipses (kind + peak ±2 min).

Reference-point spot checks (hand-verified published circumstances, at least):
- 2026-08-12 total eclipse: totality in northern Spain; Madrid partial > 90 %; path sample near (43° N, 5° W).
- 2027-08-02 total eclipse: totality Luxor ≈ 6 min 22 s; Gibraltar-area centerline crossing.
- 2028-07-22 total eclipse: Sydney in path of totality.
- Local circumstances: for 3 cities per eclipse, compare `searchLocalSolarEclipse` times against published values (±2 min).

### 17.2 Path-sampling tests

For the three eclipses above: every `PathSample.point` must itself evaluate as TOTAL via local search (self-consistency); consecutive samples < 400 km apart; path passes within 50 km of published centerline reference points; runtime budget assertion (< 60 s per eclipse on CI).

### 17.3 Source parsers

Checked-in HTTP fixture files (real captured responses, in `commonTest/resources/fixtures/`): SWPC products array-of-arrays (both Kp files), OVATION json (truncated grid + full-size), EONET events incl. a Polygon-geometry event and `closed` events, JPL `sbdb_query` comet response incl. rows with missing `M1`/`K1`. Tests: correct parse, graceful behavior on truncated/garbage payloads (diagnostic recorded, no crash, previous data retained). A `FakeClock` and fake `source_state` drive expiry logic tests.

### 17.3b Comet propagation (new — validates D12's compute path)

The Kepler propagator is the one piece of new astronomy the project owns outright, so it gets its own oracle: **JPL Horizons**. Capture Horizons ephemerides (positions + T-mag, daily over one year) for four comets spanning the eccentricity regimes — one short-period elliptical (e.g. 2P/Encke), one near-parabolic (e < 1 but > 0.99), one genuinely hyperbolic (e > 1.02, i.e. an interstellar object — note a *dynamically-new* comet has e ≈ 1.0001 and belongs in the near-parabolic slot, not this one), one with perihelion inside 0.5 au — and check them in as fixtures.

Assertions: heliocentric distance `r` within 0.001 au; geocentric `delta` within 0.002 au; RA/Dec within 0.05°; computed magnitude within 0.1 mag of Horizons' T-mag (both use the same M1/K1 formula, so this mostly tests the geometry). Solver properties, which the universal-variable formulation makes clean to state because there are no branches: converges in < 30 iterations for every e ∈ {0.1, 0.5, 0.9, 0.98, 0.999, 1.0, 1.001, 1.05, 3.0} across the full range of `dt`; `r(e)` varies continuously as e sweeps through 1.0 at fixed q and dt (no step > 1e-6 au between adjacent samples) — the specific failure the branchless formulation exists to prevent; and non-convergence drops the comet with a diagnostic rather than emitting a garbage position.

### 17.4 Visibility & rules

- Aurora: dipole-latitude function vs. frozen expected values computed with the Appendix D formula (Tromsø 67.5°, Berlin 52.2°, Calgary 57.4°, Munich 48.2° — tolerance ±0.2°); Kp rule monotonicity; OVATION bilinear lookup edge cases (lon wrap 359→0, poles).
- Eclipse travel: synthetic location 200 km from a path sample → travelDistanceKm within ±10 %.
- Meteor quality rubric: PER 2026 (peak ~Aug 12–13, new-moon conditions) from 50° N → EXCELLENT; from 45° S → NONE/MARGINAL (radiant).
- Rule engine: golden JSON round-trip for every `Cond` type; unknown-type import → disabled rule; evaluation truth-table tests per condition; dedup key collision scenario (§9.3); default rules (§9.6) evaluated against a synthetic 2-year occurrence set with expected match counts.
- Comet: quality rubric across the magnitude/altitude/moon matrix; a far-southern-declination comet evaluated from 52° N returns NONE with a stated reason and null travel fields; a bright comet only ever above the horizon in daylight returns NONE.
- Planner: reconciliation cases — occurrence moved, occurrence deleted, rule disabled, device-off missed window → immediate vs MISSED; **a lead computed in the past is dropped, not fired** (§7.4.3); **two consecutive comet refreshes 30 days apart produce identical `peakMagDate` and therefore zero re-plans** (the regression test for the orbit-anchored scan).

### 17.5 Platform smoke tests

Android instrumented (small), run against **both flavours**: alarm registers & receiver posts notification (substitute a fake `AlarmScheduler` per §10.2 and fire the receiver directly; assert via `NotificationManager`); **`canScheduleExact() == false` → WorkManager path taken, row marked APPROXIMATE, hedged copy rendered** (this is the default state of the `play` flavour and must not be an afterthought); permission granted mid-life → re-plan promotes rows back to EXACT; boot-receiver re-registration; SAF export/import round-trip. Desktop: DBus notification call succeeds on a CI container with a mock notification daemon (or skip-if-no-dbus guard); settings round-trip.

### 17.5b Build & distribution checks (CI)

- `assembleFossRelease` and `assemblePlayRelease` both succeed, and a three-part check keeps D13 honest over time: (a) parse both merged manifests and diff the permissions as **(name, maxSdkVersion) pairs** — the only permitted delta is the exact-alarm entries, and comparing bare names would miss the `maxSdkVersion="32"` attribute; (b) assert `src/fossMain/` and `src/playMain/` contain **no `.kt` files** — flavour-specific code is the actual drift risk, and merged manifests cannot see it; (c) assert both variants resolve to **identical dependency sets** (`./gradlew :androidApp:dependencies` per variant, normalised and compared).
- Reproducibility smoke check: build `fossRelease` twice in a clean workspace and assert identical APK hashes (excluding the signature block). Failing this blocks release, per §15.4.
- A dependency-licence report (Gradle licence plugin) fails the build on any dependency whose licence is not on an allowlist — the automated enforcement of P6/§16.

### 17.6 Determinism guard

A test that runs the full pipeline (fixtures → sources → visibility → rules → planner) twice and asserts byte-identical planned_notification sets — protects the natural-key/dedup design.

---

## 18. Implementation milestones

Ordered to put risk first (astronomy correctness, F-Droid packaging) and to yield a usable app early. Each milestone ends with its listed acceptance tests green. Do not start a milestone before the previous one's acceptance is met.

**M0 — Skeleton & CI (small).** Repo layout §15.1 **including both product flavours and their two manifests** (cheap now, tedious to retrofit), version catalog §15.2, empty `:core` with vendored `astronomy.kt` compiling in commonMain for androidTarget+jvm, SQLDelight wired with schema §11, CI running commonTest on JVM plus the manifest-diff and licence-allowlist checks (§17.5b). *Accept:* `./gradlew check` green; both flavours assemble; hello-world Compose app boots on both platforms.

**M1 — Astronomy core (the risk burn-down).** EclipseSource incl. path sampling §7.1.3, MeteorShowerSource, MoonEventSource, ConjunctionSource, **Kepler propagator §7.4.2**; geo helpers; golden tests §17.1–17.2 and §17.3b green. *Accept:* all golden tests; path sampling within runtime budget; comet propagation matches Horizons fixtures.

**M2 — Visibility + rules + planner.** All §8 models (aurora model behind fixture data), full §9 engine, planner + dedup, default rules, determinism guard §17.6. *Accept:* §17.4 green; CLI-style debug command in desktop app printing next 3 years of matches for a hardcoded location.

**M3 — Android MVP.** Upcoming/EventDetail/Settings/Locations/Onboarding (no Map tab, RuleEditor read-only list with enable/disable + edit of schedule leads only), alarms + boot receiver + WorkManager loop, **both `AlarmScheduler` paths incl. the APPROXIMATE fallback (§10.2)**, notifications with copy §10.5, POST_NOTIFICATIONS + exact-alarm + location-disclosure flows. *Accept:* eclipse & shower reminders fire correctly on a device with app killed, **in both flavours and with the exact-alarm permission both granted and denied**; §17.5 Android tests green except the SAF export/import round-trip (that ships in M5). **This is the first dogfood-able build.**

**M4 — Polled sources.** AuroraSource (tiered polling, OVATION persistence), CometSource (§7.4 discovery + occurrence construction on top of M1's propagator), EonetSource + their visibility models live; Sources settings screen with diagnostics; aurora nowcast notification path. *Accept:* §17.3 green; simulated high-Kp fixture produces a nowcast notification within one poll cycle; a known bright-comet fixture produces an occurrence with a sane peak date.

**M5 — Full RuleEditor (Android) + sync.** Structured condition builder §13.4 with live preview; export/import §12. *Accept:* build the §9.6 "worth a trip" rule from scratch via UI; two-device (or wiped-emulator) sync round-trip keeps history deduped.

**M6 — Desktop app.** Overview, Rules + Settings screens (reusing core view-models with desktop layouts), Map §14.1, Timeline §14.2, Aurora dashboard §14.4, Sky chart §14.3 (in that order), tray/autostart/DBus notifications, "while you were away". *Accept:* eclipse path for 2027-08-02 renders correctly vs reference map; dashboard reflects fixture Kp; runs under Flatpak locally.

**M7 — Release engineering (now covers two stores; start the non-engineering parts during M3).** Signing key generated locally and reproducible builds verified (§15.4) — this gates everything else. F-Droid metadata + RFP/`fdroiddata` merge request. Play Console: account + identity verification, the 12-tester closed test if applicable, hosted privacy policy, Data Safety form, IARC rating, AAB upload. Android Developer Verification registration for package + key. Flatpak manifest finalized, `.deb`/`.rpm` artifacts, About/licenses screen, NOTICE. *Accept:* `fdroid build` succeeds in the official builder Docker image; two clean `fossRelease` builds are byte-identical; `playRelease` AAB passes Play pre-launch report with no policy warnings; flatpak-builder produces an installable bundle.

*Calendar dependency:* the Play tester gate (12 people × 14 continuous days) and identity verification run on wall-clock time, not developer time. Begin them in parallel with M3 so they are not the critical path at M7.

Suggested v1.1 backlog (do not build now): permitted observed-magnitude comet source (R12), Android Map tab, λ☉-refined shower peaks, Glance widget, star catalog for sky chart, weather integration, light-pollution overlay, non-gravitational terms in comet propagation.

---

## 19. Risks & open questions

| # | Risk / question | Impact | Mitigation / owner decision needed |
|---|---|---|---|
| R1 | **Path-sampling runtime on low-end Android** (10k local-eclipse searches) | M1 slip | Benchmark early in M1; fallbacks: coarser first pass near ecliptic band only, compute on first app open with progress UI, or precompute per-eclipse paths at build time into a bundled resource (still license-clean — our own computation) |
| R2 | **Stellarium showers.json field semantics** (drift units, per-year entries) drift from this spec | Wrong peak data | Verify against Stellarium plugin source at implementation; goldens for PER/GEM/QUA catch regressions |
| R3 | **SWPC / JPL format drift** (SWPC has changed product layouts before) | Aurora or comets silently broken | Diagnostics surface parse failures in UI (§6.2); check the `signature` block in JPL responses; fixtures pin expectations |
| R4 | **Aurora alert latency on Doze-heavy OEMs** | Missed aurora | Documented honesty (§10.1); `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` is not merely deferred but **excluded** — Play prohibits it for this app category (§10.2) |
| R5 | **GPL choice locks out future non-GPL reuse of core** | Strategic | Accepted trade-off (D8). GPL does **not** block selling the app (§16), only non-GPL relicensing. If that ever matters: replace Stellarium's catalog with a self-curated IAU-MDC-derived dataset — that is the single GPL-infecting asset |
| R6 | **Predicted comet magnitudes are wrong exactly when it matters** (dynamically-new comets, outbursts) | Feature credibility | Honest UI copy is mandatory, not optional (§7.4.4); comets are independently disableable in Sources settings; R12 is the real fix |
| R11 | **Android Developer Verification** enforcement (2026-09-30 in BR/ID/SG/TH, global through 2027) | F-Droid installs and updates degrade to "advanced flow" in enforcing regions | Register package name + signing key before 2026-09-30. Holding one developer-owned key across both stores (§15.4) makes this a single registration. Note this is a policy position as much as a technical one — F-Droid formally opposes the scheme; the owner should decide knowingly |
| R12 | **No permissively licensed source of *observed* comet magnitudes** | Comet feature stays prediction-only | v1.1: ask Observatory Črni Vrh (COBS) for written permission covering commercial use, or evaluate MPC (licence status murky — verify before relying on it). Until one lands, do not reintroduce COBS (D12) |
| R13 | **Play review rejects or delays a release** on a policy the app believes it satisfies | Release slip; F-Droid unaffected | Flavours mean the F-Droid channel always ships regardless. Highest-probability triggers are exact alarms (already mitigated by not requesting `USE_EXACT_ALARM`) and Data Safety inconsistency (keep the form accurate as sources change) |
| R7 | **Geomagnetic pole constant ages** (~0.1°/yr) | Tiny accuracy drift | Constant documented with epoch; update on IGRF releases; error ≪ model error of the 2°/Kp rule |
| R8 | **ComposeNativeTray / two-slices maintenance status** | Desktop niceties break | Both isolated behind tiny interfaces; notify-send + no-tray degradation paths specified (§10.3) |
| R9 | Name "Skyward" & app id are placeholders | Branding | Owner to decide **before first publication on either store** — the id is immutable on both, and Play additionally locks the developer identity to it |
| R10 | Sky chart scope creep (star catalogs, constellations) | M6 slip | v1 explicitly starless (§14.3); resist |
| R14 | **Flavour drift** — the `play`/`foss` split quietly accumulating feature differences | Violates D13; doubles the test surface | The manifest-diff CI check (§17.5b) fails the build on any non-permission divergence. Removing that check requires an ADR |

**Open questions for the owner (non-blocking, defaults chosen):** final app name/id (R9); default travel radius in shipped rules (currently 500 km eclipses / 200 km aurora); whether EONET wildfires should be on by default (currently on); and the R11 policy question — whether to register for Developer Verification at all — which is a values decision, not a technical one.

---

## 20. Appendices

### A. Kp → visibility latitude quick table (from §8.4 formula)

| Kp | Visible down to \|λgm\| | Cities near that dipole latitude (computed with Appendix D formula) |
|---|---|---|
| 2 | 62° | Trondheim (63.1°), Anchorage (61.9°) |
| 4 | 58° | Edinburgh (58.1°), Winnipeg (58.0°), Helsinki (57.8°), Calgary (57.4°) |
| 6.5 | 53° | London (53.4°), Hamburg (53.7°), Seattle (53.0°), Minneapolis (53.4°) |
| 8 | 50° | Chicago (50.7°), Paris (50.4°) |
| 9 | 48° | Munich (48.2°) |

(Dipole geomagnetic latitude, not geographic — in Europe it runs within ~±2° of geographic, in central North America several degrees *above* it; the formula handles all of this. These city values double as test fixtures, §17.4.)

### B. Worked example — the headline use case

User in Münster (52.0° N, 7.6° E), rule "Total & annular eclipses — worth a trip" (§9.6, 500 km):
1. EclipseSource emits `se:20260812` (total; path Greenland → Iceland → northern Spain).
2. Visibility at Münster: local search → deep partial, quality GOOD; nearest `centralPath` sample lies in northern Spain, on the order of 1 300 km SSW → `ReachableWithin(500, EXCELLENT)` is false → only the "Partial eclipse overhead" rule matches → notifications at 7d/1d/2h anchored on local peak, body includes the travel line ("totality ≈ 1 300 km SSW").
3. A user in Bordeaux (≈ 300–400 km from the same path) *does* match the "worth a trip" rule and additionally gets the long-lead 180d/30d schedule.
4. Caution for the implementer: the distances in this appendix are order-of-magnitude prose, not test oracles. Acceptance tests must pin expected distances from real computed geometry (§17.4), never from this text.

### C. SWPC parsing sketch (products format)

```kotlin
// [["time_tag","kp","observed","noaa_scale"],["2026-08-12 12:00:00","5.33","predicted","G1"],...]
val rows: List<List<String>> = json.decodeFromString(raw)
val header = rows.first(); val idx = header.withIndex().associate { (i, k) -> k to i }
rows.drop(1).map { r -> KpSlot(
    time = parseSwpcTime(r[idx.getValue("time_tag")]),   // "yyyy-MM-dd HH:mm:ss" UTC, no zone suffix
    kp = r[idx.getValue("kp")].toDouble(),
    state = r[idx.getValue("observed")],
    scale = idx["noaa_scale"]?.let { r.getOrNull(it) }   // column may be absent or short row
)}
```

### D. Dipole geomagnetic latitude (reference implementation)

```kotlin
const val GM_POLE_LAT = 80.85   // deg N, IGRF-14/WMM2025 epoch 2025.0 (NCEI)
const val GM_POLE_LON = -72.76  // deg E
fun geomagneticLatitude(p: GeoPoint): Double {
    val lat = p.latDeg.toRadians(); val poleLat = GM_POLE_LAT.toRadians()
    val dLon = (p.lonDeg - GM_POLE_LON).toRadians()
    return asin(sin(lat)*sin(poleLat) + cos(lat)*cos(poleLat)*cos(dLon)).toDegrees()
}
```

### E. Reference URLs (implementation-time re-verification list)

- EONET docs: https://eonet.gsfc.nasa.gov/docs/v3 · events: https://eonet.gsfc.nasa.gov/api/v3/events
- SWPC indexes: https://services.swpc.noaa.gov/products/ · https://services.swpc.noaa.gov/json/
- SWPC aurora tips (Kp/latitude rule): https://www.spaceweather.gov/content/tips-viewing-aurora
- Geomagnetic poles (NCEI): https://www.ncei.noaa.gov/products/wandering-geomagnetic-poles
- Astronomy Engine: https://github.com/cosinekitty/astronomy (Kotlin source under `source/kotlin/`)
- Stellarium meteor showers plugin (showers.json + parsing source): https://github.com/Stellarium/stellarium (plugins/MeteorShowers)
- GSFC eclipse canon: https://eclipse.gsfc.nasa.gov/SEpubs/5MKSE.html (solar) · 5MKLE.html (lunar)
- JPL SBDB query API (comet discovery): https://ssd-api.jpl.nasa.gov/doc/sbdb_query.html · single-object: https://ssd-api.jpl.nasa.gov/doc/sbdb.html
- JPL Horizons (comet test oracle only, §17.3b): https://ssd-api.jpl.nasa.gov/doc/horizons.html
- F-Droid inclusion policy: https://f-droid.org/docs/Inclusion_Policy/ · reproducible builds: https://f-droid.org/docs/Reproducible_Builds/
- Play target API requirements: https://support.google.com/googleplay/android-developer/answer/11926878 · sensitive permissions (exact alarms): https://support.google.com/googleplay/android-developer/answer/16558241
- Android Developer Verification (R11): https://developer.android.com/developer-verification/guides
- OSM tile policy (only relevant if v1.1 adds tiles): https://operations.osmfoundation.org/policies/tiles/
- Natural Earth: https://www.naturalearthdata.com/downloads/

*— end of document —*
