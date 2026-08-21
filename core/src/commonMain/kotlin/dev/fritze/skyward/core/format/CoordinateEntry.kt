package dev.fritze.skyward.core.format

import dev.fritze.skyward.core.model.GeoPoint

/** Which half of a [GeoPoint] a typed coordinate is meant to become. */
enum class CoordinateAxis { LATITUDE, LONGITUDE }

/**
 * The result of interpreting what a user has typed into a latitude or
 * longitude field: the parsed value if it is usable, and the message to show
 * beneath the field if it isn't.
 *
 * §13.1's location editor is "map-less: search box + lat/lon"; with no
 * geocoding source defined in §7 the search box doesn't exist, which leaves
 * typed coordinates as the primary way a location is created. That makes the
 * range rule ([GeoPoint]: "lon in `[-180, 180)`") a piece of domain
 * behaviour the frontends must not each re-derive (P2) -- Android and desktop
 * had drifted apart on the exclusive upper bound already.
 */
data class CoordinateEntry(val degrees: Double?, val error: String?) {
    /** True once the field holds something that can't become a coordinate -- what a text field's `isError` wants. */
    val isError: Boolean get() = error != null
}

/**
 * Parses one coordinate field's [text] for [axis].
 *
 * A blank field is neither valid nor an error: it is a field the user hasn't
 * filled in yet, and colouring it red before they have typed anything reads
 * as a complaint rather than as help. Callers gate Save on [degrees] being
 * non-null, which blank already fails.
 */
fun parseCoordinate(text: String, axis: CoordinateAxis): CoordinateEntry {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return CoordinateEntry(degrees = null, error = null)
    val value = trimmed.toDoubleOrNull()
        ?: return CoordinateEntry(degrees = null, error = "Enter a number in decimal degrees, like ${axis.example}.")
    if (!value.isFinite()) return CoordinateEntry(degrees = null, error = "Enter a number in decimal degrees, like ${axis.example}.")
    return when (axis) {
        CoordinateAxis.LATITUDE ->
            if (value in -90.0..90.0) CoordinateEntry(value, null)
            else CoordinateEntry(null, "Latitude runs from -90 (south pole) to 90 (north pole).")
        // §5's upper bound is exclusive: 180 is the same meridian as -180, and
        // storing it would make two saved locations that are the same place
        // compare unequal.
        CoordinateAxis.LONGITUDE ->
            if (value >= -180.0 && value < 180.0) CoordinateEntry(value, null)
            else CoordinateEntry(null, "Longitude runs from -180 up to (but not including) 180.")
    }
}

/** A plausible value to show in the "enter a number" message, so the expected format is visible rather than described. */
private val CoordinateAxis.example: String
    get() = when (this) {
        CoordinateAxis.LATITUDE -> "52.52"
        CoordinateAxis.LONGITUDE -> "13.405"
    }

/**
 * Toggles the hemisphere of a typed coordinate by adding or removing its
 * leading minus.
 *
 * It edits the text rather than reformatting the parsed number, so "52.520"
 * doesn't collapse to "52.52" (or "1e2" to "100.0") under the user's cursor
 * just because they asked for the other side of the equator. Frontends need
 * this because a numeric soft keyboard is not obliged to offer a minus key --
 * without a sign affordance, the southern and western hemispheres can be
 * untypeable.
 */
fun flipCoordinateSign(text: String): String {
    val trimmed = text.trim()
    return if (trimmed.startsWith("-")) trimmed.removePrefix("-").trim() else "-$trimmed"
}
