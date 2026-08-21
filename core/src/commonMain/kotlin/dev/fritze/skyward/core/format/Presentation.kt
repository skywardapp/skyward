package dev.fritze.skyward.core.format

import dev.fritze.skyward.core.model.Certainty
import dev.fritze.skyward.core.model.CometPayload
import dev.fritze.skyward.core.model.LocalDetails
import dev.fritze.skyward.core.model.Phenomenon
import dev.fritze.skyward.core.model.Quality
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Instant

/**
 * §5: "timezone conversion happens only at the UI/notification edge" — the
 * on-screen half of that edge, shared by both frontends the way §4.1's
 * visibility-model map is.
 *
 * These lived in `desktopApp`'s `ui/common/Formatting.kt`, with Android
 * carrying partial private copies of some and nothing at all for others.
 * Issue #53 is what that drift looks like from a user's seat: an Android
 * detail screen printing `EXCELLENT` and a bare `2027-02-01T00:00:00Z` next
 * to a desktop pane that formats both. A frontend may lay these out
 * differently — P2 says it may not word them differently.
 *
 * Everything here is a pure presentation helper over already-computed domain
 * values; no domain logic lives in this file (§4.2). Most return `String`;
 * [localDetailLines] returns a list of them and [relativeChangeAfter]
 * returns the instant a caller should recompute at.
 */

/** Kept in one place so the two frontends cannot drift apart on names (§4.1). */
fun phenomenonLabel(phenomenon: Phenomenon): String = when (phenomenon) {
    Phenomenon.SOLAR_ECLIPSE -> "Solar eclipse"
    Phenomenon.LUNAR_ECLIPSE -> "Lunar eclipse"
    Phenomenon.AURORA -> "Aurora"
    Phenomenon.METEOR_SHOWER -> "Meteor shower"
    Phenomenon.COMET -> "Comet"
    Phenomenon.MOON_EVENT -> "Supermoon"
    Phenomenon.CONJUNCTION -> "Conjunction"
    Phenomenon.TERRESTRIAL -> "Earth event"
}

/** §8.1's [Quality] as a user reads it — never the enum constant itself. */
fun qualityLabel(quality: Quality): String = when (quality) {
    Quality.NONE -> "Not visible"
    Quality.MARGINAL -> "Marginal"
    Quality.GOOD -> "Good"
    Quality.EXCELLENT -> "Excellent"
}

/**
 * The human name for an `EventSource.id`. This existed once per frontend,
 * byte-identical, under a comment asking the next editor to keep it that way
 * — the same §4.1 finding as [phenomenonLabel], one file later.
 *
 * Unknown ids fall through to the id itself: a source added to `:core` and
 * not yet named here should read as an unfamiliar row in Settings, not
 * vanish behind a blank label.
 */
fun sourceDisplayName(id: String): String = when (id) {
    "swpc" -> "Aurora (NOAA SWPC)"
    "jpl" -> "Comets (JPL)"
    "eonet" -> "Terrestrial events (NASA EONET)"
    "eclipse" -> "Eclipses"
    "meteors" -> "Meteor showers"
    "moon" -> "Moon events"
    "conjunctions" -> "Conjunctions"
    else -> id
}

/** §6.1's [Certainty], phrased as the promise it actually makes to the user. */
fun certaintyLabel(certainty: Certainty): String = when (certainty) {
    Certainty.CERTAIN -> "Ephemeris-derived"
    Certainty.FORECAST -> "Forecast — may change"
}

private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

fun monthAbbreviation(monthNumber: Int): String = MONTHS[(monthNumber - 1).coerceIn(0, 11)]

// `.month.number` / `.day` are the non-deprecated kotlinx-datetime spellings but
// don't resolve against this project's version; core/sources uses
// monthNumber/dayOfMonth for the same reason. Keep both in step.
fun formatDate(instant: Instant, zone: TimeZone): String {
    val local = instant.toLocalDateTime(zone)
    @Suppress("DEPRECATION")
    return "${local.dayOfMonth} ${monthAbbreviation(local.monthNumber)} ${local.year}"
}

fun formatDayAndMonth(instant: Instant, zone: TimeZone): String {
    val local = instant.toLocalDateTime(zone)
    @Suppress("DEPRECATION")
    return "${local.dayOfMonth} ${monthAbbreviation(local.monthNumber)}"
}

fun formatTime(instant: Instant, zone: TimeZone): String = instant.toLocalDateTime(zone).hhmm()

fun formatDateTime(instant: Instant, zone: TimeZone): String = "${formatDate(instant, zone)}, ${formatTime(instant, zone)}"

fun LocalDateTime.hhmm(): String = "${hour.toString().padStart(2, '0')}:${minute.toString().padStart(2, '0')}"

/**
 * "in 3 days" / "in 4 h" / "in 25 min" / "now" / "3 days ago" — the countdown
 * used on cards, the timeline and EventDetail.
 */
fun formatRelative(from: Instant, to: Instant): String {
    val delta = to - from
    val magnitude = abs(delta.inWholeSeconds)
    val text = when {
        magnitude < 60 -> "now"
        magnitude < 3600 -> "${(magnitude / 60)} min"
        magnitude < 86_400 -> "${(magnitude / 3600)} h"
        magnitude < 86_400 * 60 -> plural(magnitude / 86_400, "day")
        else -> plural((magnitude / 86_400 / 30.44).roundToInt().toLong(), "month")
    }
    return when {
        text == "now" -> "now"
        delta.isNegative() -> "$text ago"
        else -> "in $text"
    }
}

/**
 * The first instant after [from] at which [formatRelative] for [to] *can*
 * read differently — what a screen showing that label has to sleep until
 * rather than polling (the same contract `UpcomingTicker`'s
 * `countdownChangeAfter` has for §13.2's coarser countdown).
 *
 * An upper bound on staleness rather than an exact change point: in the
 * months bucket the label survives many of the day rollovers this returns,
 * so a caller wakes for a recomputation that changes nothing. That costs one
 * wake per day; being wrong the other way would leave a stale countdown on
 * screen, which is the bug this exists to prevent. `PresentationTest` pins
 * the direction that matters — the label just before the returned instant
 * still equals the label at [from].
 */
fun relativeChangeAfter(from: Instant, to: Instant): Instant {
    val delta = to - from
    if (delta.isNegative()) {
        // Past: the label coarsens as the gap grows, so the change lands at
        // the next whole unit *after* [to].
        val elapsed = -delta
        return when {
            elapsed < 1.minutes -> to + 1.minutes
            elapsed < 1.hours -> to + (elapsed.inWholeMinutes + 1).minutes
            elapsed < 1.days -> to + (elapsed.inWholeHours + 1).hours
            else -> to + (elapsed.inWholeDays + 1).days
        }
    }
    // Future: at `to - wholeUnits` the count still reads `wholeUnits` (the
    // division truncates), so the change lands one instant later — without the
    // epsilon a caller waking exactly on a rollover would compute that same
    // instant as its next boundary and spin.
    return when {
        delta < 1.minutes -> to + 1.minutes // "now" holds until a minute has passed
        delta < 1.hours -> to - delta.inWholeMinutes.minutes + BOUNDARY_EPSILON
        delta < 1.days -> to - delta.inWholeHours.hours + BOUNDARY_EPSILON
        else -> to - delta.inWholeDays.days + BOUNDARY_EPSILON
    }
}

private val BOUNDARY_EPSILON = 1.nanoseconds

fun formatDurationShort(duration: Duration): String {
    val seconds = duration.inWholeSeconds
    return when {
        seconds < 60 -> "$seconds s"
        seconds < 3600 -> "${seconds / 60} min"
        seconds % 3600 == 0L -> "${seconds / 3600} h"
        else -> "${seconds / 3600} h ${(seconds % 3600) / 60} min"
    }
}

fun formatDistanceKm(km: Double): String = when {
    km < 10 -> "${(km * 10).roundToInt() / 10.0} km"
    km < 1000 -> "${km.roundToInt()} km"
    else -> "${(km / 100).roundToInt() / 10.0} thousand km"
}

private fun plural(count: Long, noun: String): String = if (count == 1L) "1 $noun" else "$count ${noun}s"

/**
 * Whole degrees render without a decimal point: the desktop version of this
 * divided by a `Double` factor unconditionally, so a sun altitude asked for
 * at zero decimals came out "41.0°".
 */
fun formatDegrees(deg: Double, decimals: Int = 0): String {
    if (decimals <= 0) return "${deg.roundToInt()}°"
    val factor = 10.0.pow(decimals.coerceAtMost(6))
    return "${(deg * factor).roundToInt() / factor}°"
}

fun formatPercent(fraction: Double): String = "${(fraction * 100).roundToInt()} %"

/**
 * Kp is reported in thirds (4.33, 5.67), so a raw `Double` renders as
 * `5.333333333333333`. Two decimals is the resolution the product actually
 * carries; shared so the Overview badge and §14.4's gauge can't drift apart.
 */
fun formatKp(kp: Double): String = "${(kp * 100).roundToInt() / 100.0}"

/**
 * §13.3's per-location times table: the phenomenon-specific lines under a
 * location's visibility verdict, rendered from the [LocalDetails] §8.1
 * already computed. One list per detail type, so a frontend styles the lines
 * without deciding what they say.
 */
fun localDetailLines(details: LocalDetails?, zone: TimeZone): List<String> = when (details) {
    is LocalDetails.SolarEclipseLocal -> listOfNotNull(
        "Max ${formatPercent(details.maxObscuration)} obscuration at ${formatTime(details.peak, zone)}, sun ${formatDegrees(details.sunAltAtPeakDeg)} up",
        details.localKind?.let { "Locally: ${it.name.lowercase()}" },
    )
    is LocalDetails.LunarEclipseLocal -> listOf(
        "Visible ${formatTime(details.visiblePhaseStart, zone)}–${formatTime(details.visiblePhaseEnd, zone)}, " +
            "moon ${formatDegrees(details.moonAltAtMidDeg)} up at mid-eclipse",
    )
    is LocalDetails.MeteorLocal -> listOfNotNull(
        details.bestViewingStart?.let { start ->
            "Best ${formatTime(start, zone)}–${details.bestViewingEnd?.let { formatTime(it, zone) } ?: "dawn"}"
        },
        "Radiant up to ${formatDegrees(details.maxRadiantAltDeg)}, Moon ${formatPercent(details.moonIllumination)}",
    )
    is LocalDetails.AuroraLocal -> listOfNotNull(
        "Geomagnetic latitude ${formatDegrees(details.geomagneticLatDeg, 1)} — needs Kp ${details.kpNeeded.roundTo(1)}",
        details.ovationProbability?.let { "OVATION overhead probability $it %" },
        details.darknessStart?.let { start ->
            "Dark ${formatTime(start, zone)}–${details.darknessEnd?.let { formatTime(it, zone) } ?: "dawn"}"
        } ?: "No astronomical darkness tonight",
    )
    is LocalDetails.CometLocal -> listOf(
        "Highest at ${formatDegrees(details.maxAltDeg)}" + (details.maxAltTime?.let { " around ${formatTime(it, zone)}" } ?: ""),
    )
    is LocalDetails.GenericLocal -> listOf(details.note)
    null -> emptyList()
}

/**
 * §7.4.4's comet block is compliance-relevant, not decorative: the magnitude
 * has to read as *predicted*, the elements it came from have to be dated, and
 * the deviation caveat is mandatory. Shared so neither frontend can quietly
 * drop a part of it.
 */
fun cometMagnitudeLine(payload: CometPayload, details: LocalDetails.CometLocal?, zone: TimeZone): String =
    "Predicted magnitude ${(details?.predictedMag ?: payload.peakMag).roundTo(1)} " +
        "(best ${payload.peakMag.roundTo(1)} around ${formatDate(payload.peakMagDate, zone)})"

/**
 * §13.3, verbatim: "from JPL elements of 2027-02-01". Dated in UTC rather
 * than the viewer's zone on purpose — this is the epoch the ephemeris is
 * stated for, a property of the data, not a time anyone goes outside at.
 */
fun cometElementsLine(payload: CometPayload, details: LocalDetails.CometLocal?): String =
    "From JPL orbital elements of ${formatDate(details?.elementEpoch ?: payload.elements.epoch, TimeZone.UTC)}."

/** §7.4.4: not optional, and not softenable. */
const val COMET_DEVIATION_CAVEAT: String =
    "Comets often deviate from prediction — treat this as a rough guide, not a guarantee."

private fun Double.roundTo(decimals: Int): Double {
    val factor = 10.0.pow(decimals.coerceIn(0, 6))
    return kotlin.math.round(this * factor) / factor
}
