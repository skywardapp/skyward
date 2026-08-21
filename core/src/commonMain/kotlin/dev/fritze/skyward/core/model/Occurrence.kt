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
) {
    init {
        val matches = when (phenomenon) {
            Phenomenon.SOLAR_ECLIPSE -> payload is SolarEclipsePayload
            Phenomenon.LUNAR_ECLIPSE -> payload is LunarEclipsePayload
            Phenomenon.AURORA -> payload is AuroraPayload
            Phenomenon.METEOR_SHOWER -> payload is MeteorShowerPayload
            Phenomenon.COMET -> payload is CometPayload
            Phenomenon.MOON_EVENT -> payload is MoonEventPayload
            Phenomenon.CONJUNCTION -> payload is ConjunctionPayload
            Phenomenon.TERRESTRIAL -> payload is TerrestrialPayload
        }
        require(matches) {
            "Occurrence $id: phenomenon $phenomenon does not accept payload type ${payload::class.simpleName}"
        }
    }
}

/**
 * §5: whether this occurrence's data has gone stale as of [now] — an
 * aurora NOWCAST ~2 h after it was issued, a THREE_DAY slot once the next
 * forecast supersedes it, a comet record 45 days after fetch, an EONET
 * event 3 days. Ephemeris events carry a null `expiresAt` and never
 * expire.
 *
 * Past that instant the row is last-known data, not current data, and the
 * §9 pipeline must stop treating it as a live prediction. Withdrawal by
 * re-fetch (`SourceRunner.upsertOccurrences`) only happens on a
 * *successful* refresh, so while a source is unreachable — SWPC down,
 * backing off up to 24 h (§6.2) — expiry is the only staleness backstop
 * there is.
 */
fun Occurrence.hasExpiredAt(now: Instant): Boolean {
    val expiresAt = expiresAt ?: return false
    return expiresAt <= now
}
