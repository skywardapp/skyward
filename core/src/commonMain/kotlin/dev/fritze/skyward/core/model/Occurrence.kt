package dev.fritze.skyward.core.model

import kotlinx.serialization.Serializable
import kotlin.time.Instant

@Serializable
data class TimeWindow(val start: Instant, val end: Instant)

/** CERTAIN = ephemeris-derived; FORECAST = aurora / comet-brightness style prediction. */
enum class Certainty { CERTAIN, FORECAST }

/**
 * One concrete event instance, source-agnostic (§5). [id] is a stable
 * natural key (§6.4) — never a random UUID, so re-fetches and cross-device
 * sync agree on identity.
 */
@Serializable
data class Occurrence(
    val id: String,
    val phenomenon: Phenomenon,
    val sourceId: String, // "eclipse", "meteors", "swpc", "jpl", "moon", "conjunctions", "eonet"
    val title: String,
    val window: TimeWindow,
    val peakTime: Instant?,
    val certainty: Certainty,
    val payload: OccurrencePayload,
    val fetchedAt: Instant,
    val expiresAt: Instant?, // null for ephemeris events, which never go stale
)
