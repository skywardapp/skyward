package dev.fritze.skyward.core.model

import kotlinx.serialization.Serializable
import kotlin.time.Instant

/**
 * Phenomenon-specific data. Sealed so the compiler forces every visibility
 * model and UI renderer to handle exactly its phenomenon (§5 design note) —
 * never stringly-type payload data.
 */
@Serializable
sealed class OccurrencePayload

enum class SolarEclipseKind { PARTIAL, ANNULAR, TOTAL, HYBRID }
enum class LunarEclipseKind { PENUMBRAL, PARTIAL, TOTAL }
enum class AuroraForecastKind { NOWCAST, THREE_DAY }
enum class MoonEventKind { SUPERMOON, MICROMOON }

@Serializable
data class PathSample(
    val time: Instant,
    val point: GeoPoint,
    val pathWidthKm: Double?, // null if not computed
    val centralDurationSec: Double?,
)

@Serializable
data class SolarEclipsePayload(
    val kind: SolarEclipseKind,
    val greatestEclipsePoint: GeoPoint,
    val greatestEclipseTime: Instant,
    /** Sampled central-path polyline for TOTAL/ANNULAR/HYBRID, else empty. §7.1.3 */
    val centralPath: List<PathSample>,
    val obscurationAtGreatest: Double, // 0..1
) : OccurrencePayload()

@Serializable
data class LunarEclipsePayload(
    val kind: LunarEclipseKind,
    val penumbralBegin: Instant,
    val partialBegin: Instant?,
    val totalBegin: Instant?,
    val totalEnd: Instant?,
    val partialEnd: Instant?,
    val penumbralEnd: Instant,
) : OccurrencePayload()

@Serializable
data class MeteorShowerPayload(
    val iauCode: String, // "PER", "GEM", "QUA"
    val name: String, // "Perseids"
    val zhr: Int?, // null -> variable; see zhrNote
    val zhrNote: String?, // e.g. "variable, 10-120"
    val radiantRaDeg: Double,
    val radiantDecDeg: Double, // J2000 at peak, drift applied
    val speedKmS: Double?,
    val parentBody: String?,
    val activityStart: Instant,
    val activityEnd: Instant, // this year's window
    val moonIlluminationAtPeak: Double, // 0..1, computed at ingest
) : OccurrencePayload()

@Serializable
data class AuroraPayload(
    val kpForecast: Double, // max Kp in the occurrence window
    val forecastKind: AuroraForecastKind,
    val issuedAt: Instant,
    // For NOWCAST: the OVATION grid is NOT stored here; the latest grid lives
    // in source_state (§11) and visibility reads it directly.
) : OccurrencePayload()

/** Heliocentric osculating elements, J2000 ecliptic, as published by JPL SBDB. */
@Serializable
data class CometElements(
    val epoch: Instant, // osculation epoch
    val eccentricity: Double, // e
    val perihelionDistanceAu: Double, // q
    val inclinationDeg: Double, // i
    val ascendingNodeDeg: Double, // Omega
    val argPerihelionDeg: Double, // omega (w)
    val tpPerihelion: Instant, // Tp - time of perihelion passage
)

/** Standard comet photometric parameters: m = M1 + 5*log10(delta) + 2.5*K1*log10(r) */
@Serializable
data class CometMagParams(val m1: Double, val k1: Double)

@Serializable
data class CometPayload(
    val designation: String, // "C/2025 K1", "12P"
    val name: String?, // "(Pons-Brooks)"
    val elements: CometElements, // JPL SBDB osculating elements - enables local propagation
    val magParams: CometMagParams, // M1/K1 (total) from SBDB
    val perihelionDate: Instant,
    // Precomputed at ingest by scanning the horizon window (§7.4.3); all PREDICTED:
    val peakMag: Double,
    val peakMagDate: Instant,
    val magAtIngest: Double,
) : OccurrencePayload()

@Serializable
data class MoonEventPayload(
    val kind: MoonEventKind, // SUPERMOON (v1); MICROMOON reserved
    val fullMoonTime: Instant,
    val perigeeTime: Instant,
    val perigeeDistanceKm: Double,
) : OccurrencePayload()

@Serializable
data class ConjunctionPayload(
    val body1: String,
    val body2: String, // "Venus", "Moon", "Jupiter"
    val minSeparationDeg: Double,
    val timeOfClosest: Instant,
) : OccurrencePayload()

@Serializable
data class TerrestrialPayload(
    val eonetId: String,
    val categoryId: String, // "volcanoes", "wildfires", "severeStorms", ...
    val categoryTitle: String,
    val latestGeometry: GeoPoint, // most recent geometry point (or polygon centroid)
    val geometryDate: Instant,
    val magnitudeValue: Double?,
    val magnitudeUnit: String?,
    val link: String, // EONET self link for "open in browser"
    val closed: Boolean,
) : OccurrencePayload()
