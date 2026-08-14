package dev.fritze.skyward.core.rules

import dev.fritze.skyward.core.model.AuroraForecastKind
import dev.fritze.skyward.core.model.Phenomenon
import dev.fritze.skyward.core.model.Quality
import dev.fritze.skyward.core.model.SolarEclipseKind
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/**
 * §9.6: shipped on first launch, editable/deletable by the user afterward.
 * IDs are stable natural keys (not random UUIDs) — these are well-known
 * system rules, not user-created ones, so a fixed identity is more useful
 * than the generic `Rule.id` doc comment's "UUID" framing (e.g. detecting
 * "has the user already customized this default" across syncs, §12.3).
 */
fun defaultRules(now: Instant): List<Rule> = listOf(
    Rule(
        id = "default:solar-eclipse-worth-a-trip",
        name = "Total & annular eclipses — worth a trip",
        enabled = true,
        phenomena = setOf(Phenomenon.SOLAR_ECLIPSE),
        locationIds = null,
        condition = Cond.And(
            listOf(
                Cond.EclipseKindIn(setOf(SolarEclipseKind.TOTAL, SolarEclipseKind.ANNULAR, SolarEclipseKind.HYBRID)),
                Cond.ReachableWithin(km = 500.0, minQualityThere = Quality.EXCELLENT),
            ),
        ),
        schedule = NotifySchedule(
            leads = listOf(180.days, 30.days, 7.days, 1.days, 2.hours),
            anchor = Anchor.PEAK,
            notifyOnFirstSeen = false,
            quietHours = null,
        ),
        createdAt = now,
        modifiedAt = now,
    ),
    Rule(
        id = "default:solar-eclipse-partial-overhead",
        name = "Partial eclipse overhead",
        enabled = true,
        phenomena = setOf(Phenomenon.SOLAR_ECLIPSE),
        locationIds = null,
        condition = Cond.VisibleAtLocation(minQuality = Quality.GOOD),
        schedule = NotifySchedule(
            leads = listOf(7.days, 1.days, 2.hours),
            anchor = Anchor.PEAK,
            notifyOnFirstSeen = false,
            quietHours = null,
        ),
        createdAt = now,
        modifiedAt = now,
    ),
    Rule(
        id = "default:lunar-eclipse-visible-from-home",
        name = "Lunar eclipse visible from home",
        enabled = true,
        phenomena = setOf(Phenomenon.LUNAR_ECLIPSE),
        locationIds = null,
        condition = Cond.VisibleAtLocation(minQuality = Quality.GOOD),
        schedule = NotifySchedule(
            leads = listOf(7.days, 1.days, 1.hours),
            anchor = Anchor.PEAK,
            notifyOnFirstSeen = false,
            quietHours = null,
        ),
        createdAt = now,
        modifiedAt = now,
    ),
    Rule(
        id = "default:meteor-showers-major-decent-conditions",
        name = "Major meteor showers, decent conditions",
        enabled = true,
        phenomena = setOf(Phenomenon.METEOR_SHOWER),
        locationIds = null,
        condition = Cond.And(listOf(Cond.ZhrAtLeast(20), Cond.VisibleAtLocation(minQuality = Quality.GOOD))),
        schedule = NotifySchedule(
            leads = listOf(3.days, 6.hours),
            anchor = Anchor.BEST_VIEWING,
            notifyOnFirstSeen = false,
            quietHours = null,
        ),
        createdAt = now,
        modifiedAt = now,
    ),
    Rule(
        id = "default:aurora-heads-up-planning",
        name = "Aurora heads-up (planning)",
        enabled = true,
        phenomena = setOf(Phenomenon.AURORA),
        locationIds = null,
        condition = Cond.And(
            listOf(
                Cond.AuroraKindIs(AuroraForecastKind.THREE_DAY),
                Cond.KpAtLeast(5.0),
                Cond.ReachableWithin(km = 200.0, minQualityThere = Quality.MARGINAL),
            ),
        ),
        schedule = NotifySchedule(
            leads = listOf(12.hours),
            anchor = Anchor.WINDOW_START,
            notifyOnFirstSeen = true,
            quietHours = null,
        ),
        createdAt = now,
        modifiedAt = now,
    ),
    Rule(
        id = "default:aurora-now",
        name = "Aurora NOW",
        enabled = true,
        phenomena = setOf(Phenomenon.AURORA),
        locationIds = null,
        condition = Cond.And(listOf(Cond.AuroraKindIs(AuroraForecastKind.NOWCAST), Cond.VisibleAtLocation(minQuality = Quality.MARGINAL))),
        schedule = NotifySchedule(
            leads = emptyList(),
            anchor = Anchor.PEAK,
            notifyOnFirstSeen = true,
            quietHours = QuietHours(fromHour = 0, toHour = 6),
        ),
        createdAt = now,
        modifiedAt = now,
    ),
    Rule(
        id = "default:comet-bright-actually-visible",
        name = "Bright comet, actually visible",
        enabled = true,
        phenomena = setOf(Phenomenon.COMET),
        locationIds = null,
        condition = Cond.And(listOf(Cond.MagnitudeAtMost(4.0), Cond.VisibleAtLocation(minQuality = Quality.MARGINAL))),
        schedule = NotifySchedule(
            leads = listOf(7.days),
            anchor = Anchor.PEAK,
            notifyOnFirstSeen = true,
            quietHours = null,
        ),
        createdAt = now,
        modifiedAt = now,
    ),
    Rule(
        id = "default:supermoon",
        name = "Supermoon",
        enabled = true,
        phenomena = setOf(Phenomenon.MOON_EVENT),
        locationIds = null,
        condition = Cond.VisibleAtLocation(minQuality = Quality.GOOD),
        schedule = NotifySchedule(
            leads = listOf(1.days),
            anchor = Anchor.PEAK,
            notifyOnFirstSeen = false,
            quietHours = null,
        ),
        createdAt = now,
        modifiedAt = now,
    ),
    Rule(
        id = "default:close-conjunctions",
        name = "Close conjunctions",
        enabled = false, // shipped disabled
        phenomena = setOf(Phenomenon.CONJUNCTION),
        locationIds = null,
        condition = Cond.VisibleAtLocation(minQuality = Quality.GOOD),
        schedule = NotifySchedule(
            leads = listOf(1.days),
            anchor = Anchor.PEAK,
            notifyOnFirstSeen = false,
            quietHours = null,
        ),
        createdAt = now,
        modifiedAt = now,
    ),
    Rule(
        id = "default:volcano-within-reach",
        name = "Volcano within reach",
        enabled = false, // shipped disabled
        phenomena = setOf(Phenomenon.TERRESTRIAL),
        locationIds = null,
        condition = Cond.And(listOf(Cond.EonetCategoryIn(setOf("volcanoes")), Cond.ReachableWithin(km = 300.0, minQualityThere = Quality.GOOD))),
        schedule = NotifySchedule(
            leads = emptyList(),
            anchor = Anchor.PEAK,
            notifyOnFirstSeen = true,
            quietHours = null,
        ),
        createdAt = now,
        modifiedAt = now,
    ),
)
