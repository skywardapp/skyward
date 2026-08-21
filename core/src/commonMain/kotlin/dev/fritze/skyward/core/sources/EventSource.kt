package dev.fritze.skyward.core.sources

import dev.fritze.skyward.core.model.Occurrence
import dev.fritze.skyward.core.model.Phenomenon
import dev.fritze.skyward.core.model.SavedLocation
import dev.fritze.skyward.core.model.TimeWindow
import kotlinx.serialization.Serializable
import kotlin.time.Duration
import kotlin.time.Instant

enum class SourceKind { COMPUTED, POLLED }

/** Per-source user settings: typed wrapper over `app_setting` key `source.<id>.settings_json` (§11). */
@Serializable
data class SourceSettings(
    val enabled: Boolean = true,
    // source-specific: EONET "categories"="volcanoes,wildfires", meteors
    // "includeMinor"="false", aurora "tier"="auto", ...
    val params: Map<String, String> = emptyMap(),
)

/**
 * Rule-derived query hints, computed by [SourceRunner] before each run by
 * scanning enabled rules' condition trees (§6.1).
 */
data class DerivedThresholds(
    val minKpOfInterest: Double?,
    val maxCometMag: Double?,
    val maxTravelKm: Double?,
    /**
     * True when at least one enabled rule sees `TERRESTRIAL` occurrences and
     * every one that does can only match within a finite distance. Not a
     * §6.1 threshold but the safety condition on using one: [maxTravelKm]
     * bounds the EONET bbox (§7.7), and a rule that matches terrestrial
     * events at any distance would lose them to that box. See
     * docs/adr/0008-eonet-bbox-narrowing-conditions.md. Defaults to the
     * conservative answer, so a caller that assembles a request by hand
     * (a rule-editor preview, a test) fetches unnarrowed.
     */
    val terrestrialRulesAreTravelBounded: Boolean = false,
)

class RefreshRequest(
    val now: Instant,
    val horizon: TimeWindow, // how far ahead the app plans; default now..now+3 years (settings)
    val locations: List<SavedLocation>, // some sources tailor queries (EONET bbox) — may be ignored
    val state: Map<String, ByteArray>, // persisted per-source state (source_state BLOBs, §11)
    val settings: SourceSettings,
    val derivedThresholds: DerivedThresholds,
) {
    // Not a data class: the default-generated equals()/hashCode() would compare
    // `state`'s ByteArray values by reference, not content (a well-known Kotlin
    // data-class-over-ByteArray pitfall), which would break change detection
    // once a runner starts comparing requests/results (M2).
    override fun equals(other: Any?): Boolean =
        this === other || (
            other is RefreshRequest &&
                now == other.now &&
                horizon == other.horizon &&
                locations == other.locations &&
                state.keys == other.state.keys &&
                state.all { (k, v) -> other.state[k]?.contentEquals(v) == true } &&
                settings == other.settings &&
                derivedThresholds == other.derivedThresholds
            )

    override fun hashCode(): Int {
        var result = now.hashCode()
        result = 31 * result + horizon.hashCode()
        result = 31 * result + locations.hashCode()
        result = 31 * result + state.entries.sumOf { (k, v) -> k.hashCode() * 31 + v.contentHashCode() }
        result = 31 * result + settings.hashCode()
        result = 31 * result + derivedThresholds.hashCode()
        return result
    }
}

@Serializable
data class SourceDiagnostics(
    val ok: Boolean,
    val httpStatus: Int? = null,
    val message: String? = null, // parse warnings, error summary — shown in Settings > Sources
    val itemCount: Int = 0,
    val lastSuccessAt: Instant? = null,
)

class RefreshResult(
    val occurrences: List<Occurrence>, // FULL current truth for this source within horizon (§6.3)
    val newState: Map<String, ByteArray>,
    val nextRefreshHint: Instant?, // POLLED sources may suggest next poll (e.g. SWPC cadence)
    val diagnostics: SourceDiagnostics,
) {
    // See RefreshRequest — same ByteArray-content-equality fix for newState.
    override fun equals(other: Any?): Boolean =
        this === other || (
            other is RefreshResult &&
                occurrences == other.occurrences &&
                newState.keys == other.newState.keys &&
                newState.all { (k, v) -> other.newState[k]?.contentEquals(v) == true } &&
                nextRefreshHint == other.nextRefreshHint &&
                diagnostics == other.diagnostics
            )

    override fun hashCode(): Int {
        var result = occurrences.hashCode()
        result = 31 * result + newState.entries.sumOf { (k, v) -> k.hashCode() * 31 + v.contentHashCode() }
        result = 31 * result + (nextRefreshHint?.hashCode() ?: 0)
        result = 31 * result + diagnostics.hashCode()
        return result
    }
}

sealed class Schedule {
    /**
     * Recompute when horizon/locations/settings change — and, because the
     * horizon is `now .. now + horizonYears` and so moves by itself, once a
     * day regardless (docs/adr/0009-daily-recompute-of-computed-sources.md).
     */
    data object OnHorizonChange : Schedule()
    data class Periodic(val interval: Duration) : Schedule()
    /** Aurora: fast when Kp is high. */
    data class Tiered(val active: Duration, val idle: Duration) : Schedule()
}

/** Produces occurrences. Implementations are stateless; state lives in the DB (§6.1). */
interface EventSource {
    val id: String // "eclipse", "meteors", "swpc", "jpl", "moon", "conjunctions", "eonet"
    val phenomena: Set<Phenomenon>
    val kind: SourceKind

    /**
     * Produce/refresh occurrences. COMPUTED sources derive them from
     * [RefreshRequest.horizon]. POLLED sources fetch HTTP and map responses;
     * they receive [RefreshRequest.state] (their persisted key-value blob)
     * and return an updated copy.
     */
    suspend fun refresh(req: RefreshRequest): RefreshResult

    /** How often [refresh] should run. COMPUTED sources return [Schedule.OnHorizonChange]. */
    fun schedule(settings: SourceSettings): Schedule
}
