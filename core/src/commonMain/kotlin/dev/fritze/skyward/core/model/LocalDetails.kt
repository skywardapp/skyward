package dev.fritze.skyward.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/** §8.1 quality rubric — model-specific; kept stable since rule conditions reference it. */
@Serializable
enum class Quality { NONE, MARGINAL, GOOD, EXCELLENT }

/**
 * Phenomenon-specific local circumstances for UI + notification copy (§8.1).
 * One subtype per phenomenon; fields are exactly what §10.5 templates and
 * detail screens (§13.3) consume.
 */
@Serializable
sealed class LocalDetails {
    @Serializable
    @SerialName("solar_eclipse_local")
    data class SolarEclipseLocal(
        val partialBegin: Instant?,
        val peak: Instant,
        val partialEnd: Instant?,
        val maxObscuration: Double,
        val sunAltAtPeakDeg: Double,
        val localKind: SolarEclipseKind?,
    ) : LocalDetails()

    @Serializable
    @SerialName("lunar_eclipse_local")
    data class LunarEclipseLocal(
        val visiblePhaseStart: Instant,
        val visiblePhaseEnd: Instant,
        val moonAltAtMidDeg: Double,
        val umbralFractionVisible: Double,
    ) : LocalDetails()

    @Serializable
    @SerialName("meteor_local")
    data class MeteorLocal(
        val bestViewingStart: Instant?, // null when no dark radiant-up window
        val bestViewingEnd: Instant?,
        val maxRadiantAltDeg: Double,
        val moonIllumination: Double,
        val moonUpDuringBest: Boolean,
    ) : LocalDetails()

    @Serializable
    @SerialName("aurora_local")
    data class AuroraLocal(
        val geomagneticLatDeg: Double,
        val kpNeeded: Double,
        val ovationProbability: Int?, // null for THREE_DAY
        val darknessStart: Instant?, // null = no astronomical night (midsummer)
        val darknessEnd: Instant?,
    ) : LocalDetails()

    @Serializable
    @SerialName("comet_local")
    data class CometLocal(
        val predictedMag: Double,
        val elementEpoch: Instant, // feeds the "as of" caveat, §7.4.4
        val maxAltDeg: Double,
        val maxAltTime: Instant?,
        val bestViewingStart: Instant?,
        val bestViewingEnd: Instant?,
    ) : LocalDetails()

    @Serializable
    @SerialName("generic_local")
    data class GenericLocal(val note: String) : LocalDetails() // supermoon, conjunction, EONET
}

/** Result of [dev.fritze.skyward.core.visibility.VisibilityModel.evaluate] (§8.1). */
@Serializable
data class VisibilityResult(
    val visibleAtLocation: Boolean, // at required baseline quality (per model, below)
    val quality: Quality,
    val localDetails: LocalDetails?,
    // Travel guidance — null when visibleAtLocation, or when travel cannot help (meteor
    // shower with radiant never up is a timing problem, not a distance problem):
    val nearestVisiblePoint: GeoPoint?,
    val travelDistanceKm: Double?, // great-circle from loc to nearestVisiblePoint
    val travelBearingDeg: Double?, // initial bearing, for "≈180 km SSE"
    val qualityAtNearestPoint: Quality?,
)
