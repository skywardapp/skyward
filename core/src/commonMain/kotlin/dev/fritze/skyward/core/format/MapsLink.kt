package dev.fritze.skyward.core.format

import dev.fritze.skyward.core.model.GeoPoint

/**
 * §13.3's travel guidance ("≈X km NE reaches good conditions"), one tap
 * further: the coordinates it names, in the two link formats a click-through
 * needs. [geoUri] lets Android hand off to whichever maps app is installed;
 * [googleMapsUrl] is what desktop opens unconditionally (no OS-level `geo:`
 * handler there) and what Android falls back to when nothing claims `geo:`.
 */
fun geoUri(point: GeoPoint, label: String): String =
    "geo:0,0?q=${point.latDeg},${point.lonDeg}(${percentEncodeQueryComponent(label)})"

fun googleMapsUrl(point: GeoPoint): String =
    "https://www.google.com/maps/search/?api=1&query=${point.latDeg},${point.lonDeg}"

/** RFC 3986 unreserved characters pass through; everything else becomes a UTF-8 percent triplet. */
private fun percentEncodeQueryComponent(text: String): String = buildString {
    for (byte in text.encodeToByteArray()) {
        val c = byte.toInt() and 0xFF
        if (c in 'A'.code..'Z'.code || c in 'a'.code..'z'.code || c in '0'.code..'9'.code ||
            c == '-'.code || c == '_'.code || c == '.'.code || c == '~'.code
        ) {
            append(c.toChar())
        } else {
            append('%').append(c.toString(16).uppercase().padStart(2, '0'))
        }
    }
}
