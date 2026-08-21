package dev.fritze.skyward.core.format

import dev.fritze.skyward.core.model.AuroraForecastKind
import dev.fritze.skyward.core.model.AuroraPayload
import dev.fritze.skyward.core.model.CometPayload
import dev.fritze.skyward.core.model.ConjunctionPayload
import dev.fritze.skyward.core.model.LocalDetails
import dev.fritze.skyward.core.model.LunarEclipseKind
import dev.fritze.skyward.core.model.LunarEclipsePayload
import dev.fritze.skyward.core.model.MeteorShowerPayload
import dev.fritze.skyward.core.model.MoonEventKind
import dev.fritze.skyward.core.model.MoonEventPayload
import dev.fritze.skyward.core.model.Occurrence
import dev.fritze.skyward.core.model.SavedLocation
import dev.fritze.skyward.core.model.SolarEclipseKind
import dev.fritze.skyward.core.model.SolarEclipsePayload
import dev.fritze.skyward.core.model.TerrestrialPayload
import dev.fritze.skyward.core.model.VisibilityResult
import dev.fritze.skyward.core.rules.Cond
import dev.fritze.skyward.core.rules.Rule
import dev.fritze.skyward.core.rules.approximateLocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/** Rendered notification text (§10.5). Never contains emoji, always ends with the location name. */
data class NotificationCopy(val title: String, val body: String)

/** Below this remaining time, eclipse copy switches from the "worth planning for" to the "today" variant. */
private val NEAR_LEAD_THRESHOLD = 3.hours

/**
 * §10.5: renders title/body for [occurrence] at [location], given its
 * precomputed [visres] and the [rule] that produced this notification (its
 * `ReachableWithin` km, if any, gates whether travel guidance is worth
 * printing). [leadUntilAnchor] is how far before the anchor this
 * notification fires — `null` for `notifyOnFirstSeen` rows, which have no
 * lead concept.
 */
fun renderNotificationCopy(
    occurrence: Occurrence,
    location: SavedLocation,
    visres: VisibilityResult,
    rule: Rule,
    fireAt: Instant,
    leadUntilAnchor: Duration?,
): NotificationCopy {
    val ruleTravelKm = reachableWithinKm(rule.condition)
    return when (val payload = occurrence.payload) {
        is SolarEclipsePayload -> solarEclipseCopy(payload, visres, location, ruleTravelKm, fireAt, isNear = leadUntilAnchor != null && leadUntilAnchor <= NEAR_LEAD_THRESHOLD)
        is LunarEclipsePayload -> lunarEclipseCopy(payload, visres, location)
        is MeteorShowerPayload -> meteorShowerCopy(payload, visres, location)
        is CometPayload -> cometCopy(payload, visres, location)
        is AuroraPayload -> if (payload.forecastKind == AuroraForecastKind.NOWCAST) {
            auroraNowcastCopy(payload, visres, location)
        } else {
            auroraThreeDayCopy(payload, visres, location)
        }
        is MoonEventPayload -> moonEventCopy(payload, location)
        is ConjunctionPayload -> conjunctionCopy(payload, location)
        is TerrestrialPayload -> terrestrialCopy(payload, visres, location)
    }
}

private fun solarEclipseCopy(
    payload: SolarEclipsePayload,
    visres: VisibilityResult,
    location: SavedLocation,
    ruleTravelKm: Double?,
    fireAt: Instant,
    isNear: Boolean,
): NotificationCopy {
    val details = visres.localDetails as? LocalDetails.SolarEclipseLocal
    val travelKm = visres.travelDistanceKm
    val travelWorthMentioning = travelKm != null && (ruleTravelKm == null || travelKm <= ruleTravelKm)

    if (!isNear) {
        val title = "${solarEclipseKindName(payload.kind)} solar eclipse — ${formatMonthDayYear(payload.greatestEclipseTime, location)}"
        val parts = mutableListOf<String>()
        if (travelWorthMentioning) {
            parts += "Path of totality passes ${travelKm.roundToKm()} km ${directionOf(visres.travelBearingDeg)}of ${location.name}."
        }
        if (details != null) {
            parts += "At ${location.name}: ${details.maxObscuration.toPercent()}% partial at ${hhmm(details.peak, location)}."
        }
        return NotificationCopy(title, parts.joinToString(" ").ifEmpty { "Visible from ${location.name}." })
    }

    val title = "Eclipse today"
    val parts = mutableListOf<String>()
    if (details?.partialBegin != null) {
        parts += "First contact at ${location.name} ${hhmm(details.partialBegin, location)}, max ${hhmm(details.peak, location)} (${details.maxObscuration.toPercent()}%)."
    }
    if (travelWorthMentioning) {
        parts += "Totality ${travelKm.roundToKm()} km ${directionOf(visres.travelBearingDeg)}— leave by ${hhmm(fireAt, location)} to be safe."
    }
    return NotificationCopy(title, parts.joinToString(" ").ifEmpty { "Today, from ${location.name}." })
}

private fun lunarEclipseCopy(payload: LunarEclipsePayload, visres: VisibilityResult, location: SavedLocation): NotificationCopy {
    val details = visres.localDetails as? LocalDetails.LunarEclipseLocal
    val title = "${lunarEclipseKindName(payload.kind)} lunar eclipse tonight"
    val parts = mutableListOf<String>()
    if (details != null) {
        parts += "Visible at ${location.name} ${hhmm(details.visiblePhaseStart, location)}–${hhmm(details.visiblePhaseEnd, location)}."
        if (details.umbralFractionVisible > 0.0) {
            parts += "${details.umbralFractionVisible.toPercent()}% of the umbral phase visible."
        }
    }
    return NotificationCopy(title, parts.joinToString(" ").ifEmpty { "Visible from ${location.name}." })
}

private fun meteorShowerCopy(payload: MeteorShowerPayload, visres: VisibilityResult, location: SavedLocation): NotificationCopy {
    val details = visres.localDetails as? LocalDetails.MeteorLocal
    val title = "${payload.name} peak tonight"
    val parts = mutableListOf<String>()
    if (details?.bestViewingStart != null && details.bestViewingEnd != null) {
        parts += "Best ${hhmm(details.bestViewingStart, location)}–${hhmm(details.bestViewingEnd, location)} at ${location.name}."
    }
    val moonIllumination = details?.moonIllumination ?: payload.moonIlluminationAtPeak
    val skyDescription = if (moonIllumination > 0.3 && (details?.moonUpDuringBest == true)) "moonlit skies" else "dark skies"
    val radiantAlt = details?.maxRadiantAltDeg?.roundToInt()
    parts += if (radiantAlt != null) {
        "Radiant up to $radiantAlt°, Moon ${moonIllumination.toPercent()}% — $skyDescription."
    } else {
        "Moon ${moonIllumination.toPercent()}% — $skyDescription."
    }
    payload.zhr?.let { parts += "Expect up to ~${zhrPhrase(it)} under clear skies." }
    return NotificationCopy(title, parts.joinToString(" "))
}

private fun cometCopy(payload: CometPayload, visres: VisibilityResult, location: SavedLocation): NotificationCopy {
    val details = visres.localDetails as? LocalDetails.CometLocal
    val mag = details?.predictedMag ?: payload.peakMag
    val title = "Comet ${payload.name ?: payload.designation} near its best"
    val parts = mutableListOf("Predicted magnitude ${mag.oneDecimal()} — ${magnitudeDescriptor(mag)}.")
    if (details?.maxAltTime != null) {
        val darkUntil = details.bestViewingEnd?.let { ", dark sky until ${hhmm(it, location)}" } ?: ""
        parts += "Highest at ${details.maxAltDeg.roundToInt()}° around ${hhmm(details.maxAltTime, location)}$darkUntil."
    }
    val epoch = details?.elementEpoch ?: payload.elements.epoch
    parts += "Prediction from JPL elements of ${formatMonthDayYear(epoch, location)}; comets often deviate."
    return NotificationCopy(title, parts.joinToString(" "))
}

private fun auroraNowcastCopy(payload: AuroraPayload, visres: VisibilityResult, location: SavedLocation): NotificationCopy {
    val details = visres.localDetails as? LocalDetails.AuroraLocal
    val title = "Aurora possible NOW at ${location.name}"
    val probability = details?.ovationProbability ?: 0
    val parts = mutableListOf("OVATION $probability% overhead probability (${hhmmUtc(payload.issuedAt)} UTC forecast).")
    if (details?.darknessStart != null) {
        parts += "Look north after full darkness (~${hhmm(details.darknessStart, location)})."
    }
    return NotificationCopy(title, parts.joinToString(" "))
}

private fun auroraThreeDayCopy(payload: AuroraPayload, visres: VisibilityResult, location: SavedLocation): NotificationCopy {
    val details = visres.localDetails as? LocalDetails.AuroraLocal
    val title = "Aurora possible — Kp ${payload.kpForecast.oneDecimal()} forecast"
    val parts = mutableListOf<String>()
    val travelKm = visres.travelDistanceKm
    parts += if (visres.visibleAtLocation) {
        "${location.name} may see it directly if skies are clear."
    } else if (travelKm != null) {
        "Best chance ${travelKm.roundToKm()} km ${directionOf(visres.travelBearingDeg)}of ${location.name}."
    } else {
        "Watch the sky from ${location.name}."
    }
    if (details?.darknessStart != null) parts += "Watch after ${hhmm(details.darknessStart, location)}."
    return NotificationCopy(title, parts.joinToString(" "))
}

private fun moonEventCopy(payload: MoonEventPayload, location: SavedLocation): NotificationCopy {
    val isSupermoon = payload.kind == MoonEventKind.SUPERMOON
    val title = if (isSupermoon) "Supermoon tonight" else "Micromoon tonight"
    val sizeWord = if (isSupermoon) "brighter and larger" else "dimmer and smaller"
    val body = "Full moon near ${if (isSupermoon) "perigee" else "apogee"} (${payload.perigeeDistanceKm.roundToInt()} km) — $sizeWord than usual, visible from ${location.name}."
    return NotificationCopy(title, body)
}

private fun conjunctionCopy(payload: ConjunctionPayload, location: SavedLocation): NotificationCopy {
    val title = "${payload.body1}–${payload.body2} conjunction"
    val separation = ((payload.minSeparationDeg * 10).roundToInt() / 10.0)
    val body = "Closest approach $separation° apart — visible from ${location.name}."
    return NotificationCopy(title, body)
}

private fun terrestrialCopy(payload: TerrestrialPayload, visres: VisibilityResult, location: SavedLocation): NotificationCopy {
    val statusWord = if (payload.closed) "resolved" else "active"
    val title = "${payload.categoryTitle}: $statusWord event nearby"
    val travelKm = visres.travelDistanceKm
    val body = if (travelKm != null) {
        "${travelKm.roundToKm()} km ${directionOf(visres.travelBearingDeg)}of ${location.name}."
    } else {
        "Near ${location.name}."
    }
    return NotificationCopy(title, body)
}

/**
 * §10.4/§10.5: the one thing rendered at fire time rather than plan time,
 * since `precision` is only known once `AlarmScheduler.schedule()` runs.
 * Prefixes every `HH:mm` time already in [body] with "around" and, only on
 * the first APPROXIMATE notification the app has ever fired, appends the
 * one-time explainer — never more than once per app version's worth of nagging.
 */
fun applyApproximateHedge(body: String, isFirstApproximateEver: Boolean): String {
    val hedged = TIME_PATTERN.replace(body) { "around ${it.value}" }
    return if (isFirstApproximateEver) {
        "$hedged Times are approximate — enable exact alarms in Settings for precise reminders."
    } else {
        hedged
    }
}

// Excludes a UTC forecast-issue timestamp (only auroraNowcastCopy's "(HH:mm UTC forecast)"
// fragment) -- that's when the data was issued, not when this alarm fires, so hedging it
// would misleadingly suggest the forecast's own issue time is approximate.
private val TIME_PATTERN = Regex("""\b\d{2}:\d{2}\b(?!\s*UTC)""")

private fun reachableWithinKm(cond: Cond): Double? = when (cond) {
    is Cond.And -> cond.all.firstNotNullOfOrNull { reachableWithinKm(it) }
    is Cond.Or -> cond.any.firstNotNullOfOrNull { reachableWithinKm(it) }
    is Cond.Not -> reachableWithinKm(cond.inner)
    is Cond.ReachableWithin -> cond.km
    else -> null
}

private fun solarEclipseKindName(kind: SolarEclipseKind) = when (kind) {
    SolarEclipseKind.PARTIAL -> "Partial"
    SolarEclipseKind.ANNULAR -> "Annular"
    SolarEclipseKind.TOTAL -> "Total"
    SolarEclipseKind.HYBRID -> "Hybrid"
}

private fun lunarEclipseKindName(kind: LunarEclipseKind) = when (kind) {
    LunarEclipseKind.PENUMBRAL -> "Penumbral"
    LunarEclipseKind.PARTIAL -> "Partial"
    LunarEclipseKind.TOTAL -> "Total"
}

/** mag 4.2 lands here — the design doc's own worked example (§10.5). */
private fun magnitudeDescriptor(mag: Double): String = when {
    mag <= 2.0 -> "naked-eye standout"
    mag <= 4.0 -> "naked-eye under dark skies"
    mag <= 6.0 -> "binocular target"
    else -> "telescope target"
}

private fun zhrPhrase(zhr: Int): String {
    val perMinute = zhr / 60.0
    return if (perMinute >= 1.0) "${perMinute.roundToInt()} meteor/min" else "1 meteor every ${(60 / zhr.coerceAtLeast(1))} min"
}

private fun Double.roundToKm(): Int = roundToInt()
private fun Double.oneDecimal(): String = ((this * 10).roundToInt() / 10.0).toString()
private fun Double.toPercent(): Int = (this * 100).roundToInt()

// Notification times are longitude-approximated rather than zone-converted: a
// notification is rendered for a saved location, which carries no tz database
// entry (§10.5).
private fun hhmm(instant: Instant, location: SavedLocation): String = approximateLocalDateTime(instant, location.point.lonDeg).hhmm()
private fun hhmmUtc(instant: Instant): String = instant.toLocalDateTime(TimeZone.UTC).hhmm()

private fun formatMonthDayYear(instant: Instant, location: SavedLocation): String {
    val local = approximateLocalDateTime(instant, location.point.lonDeg)
    // .month.number / .day (the non-deprecated kotlinx-datetime replacements) don't resolve
    // against this project's kotlinx-datetime version -- monthNumber/dayOfMonth are deprecated
    // but the only ones that actually compile here.
    @Suppress("DEPRECATION")
    return "${monthAbbreviation(local.monthNumber)} ${local.dayOfMonth}, ${local.year}"
}

/** [compassOf] followed by a trailing space, or "" when [bearingDeg] is null -- avoids a double space at call sites that join it against a following word ("... of Home"). */
private fun directionOf(bearingDeg: Double?): String = compassOf(bearingDeg).let { if (it.isEmpty()) "" else "$it " }

/** 16-point compass abbreviation for a bearing in `[0, 360)`, e.g. "SSE" (§8.1: `travelBearingDeg`'s own doc comment). */
fun compassOf(bearingDeg: Double?): String {
    if (bearingDeg == null) return ""
    val normalized = ((bearingDeg % 360.0) + 360.0) % 360.0
    val index = ((normalized / 22.5) + 0.5).toInt() % 16
    return COMPASS_POINTS[index]
}

private val COMPASS_POINTS = arrayOf("N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE", "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW")
