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
)

data class RefreshRequest(
    val now: Instant,
    val horizon: TimeWindow, // how far ahead the app plans; default now..now+3 years (settings)
    val locations: List<SavedLocation>, // some sources tailor queries (EONET bbox) — may be ignored
    val state: Map<String, ByteArray>, // persisted per-source state (source_state BLOBs, §11)
    val settings: SourceSettings,
    val derivedThresholds: DerivedThresholds,
)

@Serializable
data class SourceDiagnostics(
    val ok: Boolean,
    val httpStatus: Int? = null,
    val message: String? = null, // parse warnings, error summary — shown in Settings > Sources
    val itemCount: Int = 0,
    val lastSuccessAt: Instant? = null,
)

data class RefreshResult(
    val occurrences: List<Occurrence>, // FULL current truth for this source within horizon (§6.3)
    val newState: Map<String, ByteArray>,
    val nextRefreshHint: Instant?, // POLLED sources may suggest next poll (e.g. SWPC cadence)
    val diagnostics: SourceDiagnostics,
)

sealed class Schedule {
    /** Recompute when horizon/locations/settings change. */
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
