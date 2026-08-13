package dev.fritze.skyward.core.rules

import dev.fritze.skyward.core.model.AuroraForecastKind
import dev.fritze.skyward.core.model.Certainty
import dev.fritze.skyward.core.model.LunarEclipseKind
import dev.fritze.skyward.core.model.Phenomenon
import dev.fritze.skyward.core.model.Quality
import dev.fritze.skyward.core.model.SolarEclipseKind
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * §9.1. A small, typed, JSON-serializable predicate AST + a notification
 * schedule per rule (D6: full rule engine, not a free-text DSL — the UI is
 * a structured builder, §13.4; this is the contract).
 */
@Serializable
data class Rule(
    val id: String, // UUID
    val name: String, // "Total eclipses within 500 km"
    val enabled: Boolean,
    val phenomena: Set<Phenomenon>, // which occurrence types this rule sees
    val locationIds: List<String>?, // null = all saved locations
    val condition: Cond, // predicate tree, below
    val schedule: NotifySchedule,
    // true for system-generated rules (one-off reminders, per-event mutes,
    // §13.3) — excluded from the Rules list UI, included in evaluation & sync
    val hidden: Boolean = false,
    val createdAt: Instant,
    val modifiedAt: Instant,
)

/**
 * Predicate tree. Serialized with kotlinx-serialization's default "type"
 * class discriminator; sync files and the DB store this JSON, so it must
 * stay backward-compatible (only add types, never rename — §12.3: an
 * unknown type on import imports that rule disabled, with a warning).
 */
@Serializable
sealed class Cond {
    @Serializable
    @SerialName("and")
    data class And(val all: List<Cond>) : Cond()

    @Serializable
    @SerialName("or")
    data class Or(val any: List<Cond>) : Cond()

    @Serializable
    @SerialName("not")
    data class Not(val inner: Cond) : Cond()

    // Visibility-derived (computed per (occurrence, location) before evaluation):
    @Serializable
    @SerialName("visible_at_location")
    data class VisibleAtLocation(val minQuality: Quality = Quality.MARGINAL) : Cond()

    /**
     * True if visible at [minQualityThere] locally, OR travelDistanceKm <=
     * [km] with qualityAtNearestPoint >= [minQualityThere].
     */
    @Serializable
    @SerialName("reachable_within")
    data class ReachableWithin(val km: Double, val minQualityThere: Quality = Quality.GOOD) : Cond()

    // Phenomenon-parameter conditions (evaluate against payload; false if payload lacks the field):
    @Serializable
    @SerialName("kp_at_least")
    data class KpAtLeast(val kp: Double) : Cond()

    @Serializable
    @SerialName("zhr_at_least")
    data class ZhrAtLeast(val zhr: Int) : Cond()

    /** Comets: tests `payload.peakMag` (the apparition's best), not `magAtIngest` (§9.1). */
    @Serializable
    @SerialName("magnitude_at_most")
    data class MagnitudeAtMost(val mag: Double) : Cond()

    @Serializable
    @SerialName("eclipse_kind_in")
    data class EclipseKindIn(val kinds: Set<SolarEclipseKind>) : Cond()

    @Serializable
    @SerialName("lunar_kind_in")
    data class LunarKindIn(val kinds: Set<LunarEclipseKind>) : Cond()

    @Serializable
    @SerialName("moon_illumination_at_most")
    data class MoonIlluminationAtMost(val fraction: Double) : Cond()

    @Serializable
    @SerialName("eonet_category_in")
    data class EonetCategoryIn(val categoryIds: Set<String>) : Cond()

    @Serializable
    @SerialName("certainty_is")
    data class CertaintyIs(val certainty: Certainty) : Cond()

    /** NOWCAST vs THREE_DAY. */
    @Serializable
    @SerialName("aurora_kind_is")
    data class AuroraKindIs(val kind: AuroraForecastKind) : Cond()

    /** Targets one specific occurrence — "add one-off extra reminder", or per-event mute under [Not] (§13.3). */
    @Serializable
    @SerialName("occurrence_id_is")
    data class OccurrenceIdIs(val id: String) : Cond()

    // Temporal/contextual:
    @Serializable
    @SerialName("peak_in_days_ahead")
    data class PeakInDaysAhead(val maxDays: Int) : Cond()

    /** "weekend" = local Fri 18:00-Mon 06:00 when [includeFridayNight], else Sat 00:00-Mon 00:00. */
    @Serializable
    @SerialName("peak_on_weekend")
    data class PeakOnWeekend(val includeFridayNight: Boolean = true) : Cond()

    /** Wraps midnight: `fromHour=22, toHour=6` means 22:00-05:59. */
    @Serializable
    @SerialName("peak_in_local_hours")
    data class PeakInLocalHours(val fromHour: Int, val toHour: Int) : Cond()
}

@Serializable
data class NotifySchedule(
    val leads: List<Duration>, // e.g. [30.days, 7.days, 1.days, 2.hours] before anchor
    val anchor: Anchor, // PEAK or WINDOW_START or BEST_VIEWING (from localDetails, falls back to PEAK)
    val notifyOnFirstSeen: Boolean, // fire as soon as occurrence first matches (aurora nowcast, comets, EONET)
    val quietHours: QuietHours?, // suppress+defer to end of quiet window (null = none)
)

@Serializable
enum class Anchor { PEAK, WINDOW_START, BEST_VIEWING }

/** Device-local suppression window. */
@Serializable
data class QuietHours(val fromHour: Int, val toHour: Int)
