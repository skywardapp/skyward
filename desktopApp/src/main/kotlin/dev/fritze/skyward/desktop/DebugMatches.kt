package dev.fritze.skyward.desktop

import dev.fritze.skyward.core.model.GeoPoint
import dev.fritze.skyward.core.model.Occurrence
import dev.fritze.skyward.core.model.Phenomenon
import dev.fritze.skyward.core.model.SavedLocation
import dev.fritze.skyward.core.model.TimeWindow
import dev.fritze.skyward.core.planner.Planner
import dev.fritze.skyward.core.rules.defaultRules
import dev.fritze.skyward.core.sources.ConjunctionSource
import dev.fritze.skyward.core.sources.DerivedThresholds
import dev.fritze.skyward.core.sources.EclipseSource
import dev.fritze.skyward.core.sources.EventSource
import dev.fritze.skyward.core.sources.MeteorShowerSource
import dev.fritze.skyward.core.sources.MoonEventSource
import dev.fritze.skyward.core.sources.RefreshRequest
import dev.fritze.skyward.core.sources.SourceSettings
import dev.fritze.skyward.core.visibility.AuroraVisibilityModel
import dev.fritze.skyward.core.visibility.CometVisibilityModel
import dev.fritze.skyward.core.visibility.ConjunctionVisibilityModel
import dev.fritze.skyward.core.visibility.LunarEclipseVisibilityModel
import dev.fritze.skyward.core.visibility.MeteorShowerVisibilityModel
import dev.fritze.skyward.core.visibility.MoonEventVisibilityModel
import dev.fritze.skyward.core.visibility.SolarEclipseVisibilityModel
import dev.fritze.skyward.core.visibility.TerrestrialVisibilityModel
import dev.fritze.skyward.core.visibility.VisibilityContext
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.TimeZone
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

/**
 * §18 M2 accept criterion: "CLI-style debug command in desktop app printing
 * next 3 years of matches for a hardcoded location." Invoke with
 * `debug-matches` as the first CLI argument (see [main]).
 *
 * The hardcoded location is Munster (52.0N, 7.6E) — Appendix B's own
 * worked example location, not an arbitrary choice.
 */
fun runDebugMatches() {
    val now = Clock.System.now()
    val horizonEnd = now + (365 * 3).days
    val horizon = TimeWindow(now, horizonEnd)
    val location = SavedLocation(
        id = "debug",
        name = "Munster",
        point = GeoPoint(52.0, 7.6),
        isPrimary = true,
        createdAt = now,
        modifiedAt = now,
    )

    val computedSources: List<EventSource> = listOf(EclipseSource(), MeteorShowerSource(), MoonEventSource(), ConjunctionSource())
    // AURORA/COMET/TERRESTRIAL have no EventSource yet (AuroraSource,
    // CometSource, EonetSource all land in M4) -- their VisibilityModels
    // are wired below regardless (M2's own scope: "aurora model behind
    // fixture data"), they just never see any occurrences from this CLI path.
    val visibilityModels = mapOf(
        Phenomenon.SOLAR_ECLIPSE to SolarEclipseVisibilityModel(),
        Phenomenon.LUNAR_ECLIPSE to LunarEclipseVisibilityModel(),
        Phenomenon.AURORA to AuroraVisibilityModel(),
        Phenomenon.METEOR_SHOWER to MeteorShowerVisibilityModel(),
        Phenomenon.COMET to CometVisibilityModel(),
        Phenomenon.MOON_EVENT to MoonEventVisibilityModel(),
        Phenomenon.CONJUNCTION to ConjunctionVisibilityModel(),
        Phenomenon.TERRESTRIAL to TerrestrialVisibilityModel(),
    )

    println("Skyward debug: matches for ${location.name} (${location.point.latDeg}, ${location.point.lonDeg}) over the next 3 years")
    println("Horizon: $now .. $horizonEnd")
    println()

    val occurrences: List<Occurrence> = runBlocking {
        computedSources.flatMap { source ->
            val result = source.refresh(
                RefreshRequest(
                    now = now,
                    horizon = horizon,
                    locations = listOf(location),
                    state = emptyMap(),
                    settings = SourceSettings(),
                    derivedThresholds = DerivedThresholds(null, null, null),
                ),
            )
            if (!result.diagnostics.ok) {
                System.err.println("source ${source.id}: ${result.diagnostics.message}")
            }
            result.occurrences
        }
    }

    val rules = defaultRules(now)
    val ctx = VisibilityContext(now = now, ovationGrid = null)
    val matches = Planner.computeMatches(occurrences, listOf(location), rules, visibilityModels, ctx)
    // Through reconcile against an empty history, i.e. exactly what a fresh
    // install would plan: that is where §7.4.3's "a lead already in the past
    // at plan time is dropped" is applied, and printing the raw candidate set
    // would list reminders the app would never actually schedule.
    val desired = Planner
        .reconcile(emptyList(), Planner.desiredNotifications(matches, now, TimeZone.currentSystemDefault()), now, occurrences.associateBy { it.id })
        .sortedBy { it.fireAt }

    println("${occurrences.size} occurrences in horizon, ${matches.size} rule matches, ${desired.size} planned notifications")
    println()

    if (desired.isEmpty()) {
        println("(no matches)")
        return
    }

    val occurrencesById = occurrences.associateBy { it.id }
    val rulesById = rules.associateBy { it.id }
    for (n in desired) {
        val occ = occurrencesById[n.occurrenceId]
        val rule = rulesById[n.ruleId]
        println("${n.fireAt}  ${rule?.name.orEmpty().padEnd(45)}  ${occ?.title.orEmpty().padEnd(30)}  ${n.body}")
    }
}
