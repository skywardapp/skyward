package dev.fritze.skyward.core.sources

import dev.fritze.skyward.core.model.AuroraPayload
import dev.fritze.skyward.core.model.CometPayload
import dev.fritze.skyward.core.model.LunarEclipsePayload
import dev.fritze.skyward.core.model.Occurrence
import dev.fritze.skyward.core.model.SolarEclipsePayload
import kotlin.math.abs
import kotlin.time.Duration.Companion.minutes

/**
 * §6.3 point 3: whether re-fetching [previous] as [fresh] is worth a full
 * re-plan. Explicitly NOT material on its own: `Comet.magAtIngest`/
 * `magParams` (display-only, changes every refresh by design, §7.4.3) and
 * `fetchedAt` — this function never looks at either, so they can't
 * trigger one. "Getting this list wrong produces notification storms"
 * (§6.3) — a refetch that isn't material must not cancel+recreate the
 * same alarms.
 */
fun isMaterialChange(previous: Occurrence, fresh: Occurrence): Boolean {
    val peakMoved = when {
        previous.peakTime == null && fresh.peakTime == null -> false
        previous.peakTime == null || fresh.peakTime == null -> true
        else -> abs((fresh.peakTime - previous.peakTime).inWholeSeconds) > 5.minutes.inWholeSeconds
    }
    if (peakMoved) return true

    val kindChanged = when {
        previous.payload is SolarEclipsePayload && fresh.payload is SolarEclipsePayload ->
            previous.payload.kind != fresh.payload.kind
        previous.payload is LunarEclipsePayload && fresh.payload is LunarEclipsePayload ->
            previous.payload.kind != fresh.payload.kind
        else -> false
    }
    if (kindChanged) return true

    if (previous.payload is AuroraPayload && fresh.payload is AuroraPayload) {
        if (abs(fresh.payload.kpForecast - previous.payload.kpForecast) >= 0.5) return true
    }

    // ADR 0015: a re-fetch that republishes different orbital elements can
    // shift CometVisibilityModel's bestViewingStart (and so the §9.3
    // BEST_VIEWING anchor) without peakMagDate ever crossing the 5-minute
    // threshold above. `elements` only changes when JPL's SBDB solution
    // itself changes, so unlike peakTime this needs no tolerance band.
    if (previous.payload is CometPayload && fresh.payload is CometPayload) {
        if (previous.payload.elements != fresh.payload.elements) return true
    }

    return false
}
