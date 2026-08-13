package dev.fritze.skyward.core.astro

import io.github.cosinekitty.astronomy.Time
import kotlin.time.Instant

/** Bridges between the stdlib [Instant] used across `:core` and Astronomy Engine's own [Time]. */
fun Instant.toAstroTime(): Time = Time.fromMillisecondsSince1970(toEpochMilliseconds())

fun Time.toInstant(): Instant = Instant.fromEpochMilliseconds(toMillisecondsSince1970())
