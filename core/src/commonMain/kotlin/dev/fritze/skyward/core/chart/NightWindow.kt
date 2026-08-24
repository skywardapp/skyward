package dev.fritze.skyward.core.chart

import dev.fritze.skyward.core.astro.darknessWindow
import dev.fritze.skyward.core.astro.toAstroTime
import dev.fritze.skyward.core.astro.toInstant
import dev.fritze.skyward.core.format.formatTime
import dev.fritze.skyward.core.model.SavedLocation
import io.github.cosinekitty.astronomy.Observer
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/** The window the sky chart's slider spans, and whether it is real astronomical darkness or the fallback. */
data class NightWindow(val start: Instant, val end: Instant, val isAstronomicalDarkness: Boolean) {
    fun describe(zone: TimeZone): String = if (isAstronomicalDarkness) {
        "astronomical darkness ${formatTime(start, zone)}–${formatTime(end, zone)}"
    } else {
        "no astronomical darkness tonight — showing ${formatTime(start, zone)}–${formatTime(end, zone)}"
    }
}

/**
 * Local noon of the day whose *night* the chart is showing — today's if it is
 * already afternoon, yesterday's otherwise, so someone looking at 01:00 gets
 * the night in progress rather than the next one.
 *
 * Noon is the useful anchor: searching forward from it lands on tonight's
 * darkness whatever the hour, and the value is constant for a whole
 * noon-to-noon period, which is what keeps the slider still.
 */
fun nightAnchor(now: Instant, zone: TimeZone): Instant {
    val local = now.toLocalDateTime(zone)
    val day = if (local.hour >= 12) local.date else local.date.minus(1, DateTimeUnit.DAY)
    return LocalDateTime(day, LocalTime(12, 0)).toInstant(zone)
}

/** [anchor] is local noon (see [nightAnchor]), never the current instant. */
fun nightWindow(location: SavedLocation, anchor: Instant): NightWindow {
    val observer = Observer(location.point.latDeg, location.point.lonDeg, 0.0)
    val darkness = darknessWindow(anchor.toAstroTime(), observer)
    return if (darkness != null) {
        NightWindow(darkness.start.toInstant(), darkness.end.toInstant(), isAstronomicalDarkness = true)
    } else {
        // High-summer latitudes: no astronomical night to span, so show the
        // conventional 18:00–06:00 evening instead. Derived from the anchor,
        // so it is the same window all night.
        NightWindow(anchor + 6.hours, anchor + 18.hours, isAstronomicalDarkness = false)
    }
}
