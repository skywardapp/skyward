package dev.fritze.skyward.core.persistence

import kotlin.time.Instant

/**
 * `Instant.toString()` is valid ISO-8601 but not fixed-width: it omits the fractional-second
 * part for whole seconds and otherwise trims trailing zeros (e.g. "...:00Z" vs "...:00.5Z" vs
 * "...:00.123Z"). SQLite compares TEXT columns lexicographically, and a fractional value can
 * sort *before* a whole-second one under that scheme ('.' < 'Z' in ASCII), which breaks the
 * `<=`/`<` range comparisons `planned_notification`'s queries rely on. This always emits exactly
 * 3 fractional digits so every stored timestamp compares correctly against every other one.
 */
internal fun Instant.toIsoFixed(): String {
    val millis = toEpochMilliseconds()
    val wholeSeconds = millis / 1000
    val fractionMillis = millis - wholeSeconds * 1000
    val base = Instant.fromEpochMilliseconds(wholeSeconds * 1000).toString()
    return base.removeSuffix("Z") + "." + fractionMillis.toString().padStart(3, '0') + "Z"
}
