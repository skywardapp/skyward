package dev.fritze.skyward.ui.rules

import dev.fritze.skyward.core.model.AuroraForecastKind
import dev.fritze.skyward.core.model.Certainty
import dev.fritze.skyward.core.model.LunarEclipseKind
import dev.fritze.skyward.core.model.Phenomenon
import dev.fritze.skyward.core.model.Quality
import dev.fritze.skyward.core.model.SolarEclipseKind
import dev.fritze.skyward.core.rules.Cond

/**
 * §13.4: "Condition builder UI = nested groups: a group is AND/OR toggle +
 * list of condition rows + 'add condition'/'add group' (NOT rendered as a
 * per-row 'invert' toggle)." [Cond.Not] is therefore not something this
 * builder constructs -- it only ever appears in hidden system rules (§13.3
 * mutes), which the RuleEditor never opens. A `Not` loaded from data it
 * didn't create (hand-edited JSON, a future app version) is kept as an
 * opaque, non-editable leaf rather than dropped, so editing an unrelated
 * part of the tree can't silently delete it.
 */
enum class GroupOp { AND, OR }

sealed class ConditionNode {
    data class Group(val op: GroupOp, val children: List<ConditionNode>) : ConditionNode()
    data class Leaf(val cond: Cond) : ConditionNode()
}

fun Cond.toNode(): ConditionNode = when (this) {
    is Cond.And -> ConditionNode.Group(GroupOp.AND, all.map { it.toNode() })
    is Cond.Or -> ConditionNode.Group(GroupOp.OR, any.map { it.toNode() })
    else -> ConditionNode.Leaf(this)
}

fun ConditionNode.toCond(): Cond = when (this) {
    is ConditionNode.Group -> if (op == GroupOp.AND) Cond.And(children.map { it.toCond() }) else Cond.Or(children.map { it.toCond() })
    is ConditionNode.Leaf -> cond
}

/** A leaf condition type offered by the "add condition" dropdown, filtered to the rule's selected phenomena. */
data class CondTypeOption(val label: String, val appliesTo: (Set<Phenomenon>) -> Boolean, val default: () -> Cond)

/**
 * Every leaf [Cond] type the builder can create, cross-referenced against
 * `RuleEngine.evaluate`'s payload casts (§9.2) for which phenomena each one
 * is meaningful for. [Cond.OccurrenceIdIs] is deliberately absent: it only
 * powers the EventDetail "mute"/"one-off reminder" hidden rules (§13.3),
 * never a user-authored one.
 */
val COND_TYPE_OPTIONS: List<CondTypeOption> = listOf(
    CondTypeOption("Visible at location", { true }, { Cond.VisibleAtLocation(Quality.GOOD) }),
    CondTypeOption("Reachable within distance", { true }, { Cond.ReachableWithin(km = 300.0, minQualityThere = Quality.GOOD) }),
    CondTypeOption("Certainty is", { true }, { Cond.CertaintyIs(Certainty.CERTAIN) }),
    CondTypeOption("Peak within N days", { true }, { Cond.PeakInDaysAhead(7) }),
    CondTypeOption("Peak falls on a weekend", { true }, { Cond.PeakOnWeekend() }),
    CondTypeOption("Peak within local hours", { true }, { Cond.PeakInLocalHours(20, 23) }),
    CondTypeOption("Kp index at least", { Phenomenon.AURORA in it }, { Cond.KpAtLeast(5.0) }),
    CondTypeOption("Aurora forecast kind is", { Phenomenon.AURORA in it }, { Cond.AuroraKindIs(AuroraForecastKind.THREE_DAY) }),
    CondTypeOption("ZHR at least", { Phenomenon.METEOR_SHOWER in it }, { Cond.ZhrAtLeast(20) }),
    CondTypeOption("Moon illumination at most", { Phenomenon.METEOR_SHOWER in it }, { Cond.MoonIlluminationAtMost(0.5) }),
    CondTypeOption("Magnitude at most (brighter than)", { Phenomenon.COMET in it }, { Cond.MagnitudeAtMost(6.0) }),
    CondTypeOption("Eclipse kind is", { Phenomenon.SOLAR_ECLIPSE in it }, { Cond.EclipseKindIn(setOf(SolarEclipseKind.TOTAL)) }),
    CondTypeOption("Lunar eclipse kind is", { Phenomenon.LUNAR_ECLIPSE in it }, { Cond.LunarKindIn(setOf(LunarEclipseKind.TOTAL)) }),
    CondTypeOption("Earth-event category", { Phenomenon.TERRESTRIAL in it }, { Cond.EonetCategoryIn(setOf("volcanoes")) }),
)

/** EonetSource.DEFAULT_CATEGORIES -- the categories the app actually fetches, so any other id would never match anything. */
val EONET_CATEGORIES: List<Pair<String, String>> = listOf(
    "volcanoes" to "Volcanoes",
    "severeStorms" to "Severe storms",
    "wildfires" to "Wildfires",
)

fun condTypeLabel(cond: Cond): String = COND_TYPE_OPTIONS.firstOrNull { it.default()::class == cond::class }?.label ?: "Unsupported condition"
