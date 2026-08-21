package dev.fritze.skyward.core.format

import dev.fritze.skyward.core.model.Phenomenon

/**
 * §4.1: presentational helpers shared by both frontends are pure functions in
 * `core/format/`. This one used to exist twice — once per frontend, kept
 * byte-identical by a comment asking the next editor to remember — which is
 * the drift §4.1 names that rule to prevent: a phenomenon renamed on one
 * platform would have gone unnoticed until a screenshot caught it.
 *
 * The `when` is exhaustive without an `else`, so adding a [Phenomenon] is a
 * compile error here rather than a silently unlabelled card.
 */
fun phenomenonLabel(phenomenon: Phenomenon): String = when (phenomenon) {
    Phenomenon.SOLAR_ECLIPSE -> "Solar eclipse"
    Phenomenon.LUNAR_ECLIPSE -> "Lunar eclipse"
    Phenomenon.AURORA -> "Aurora"
    Phenomenon.METEOR_SHOWER -> "Meteor shower"
    Phenomenon.COMET -> "Comet"
    Phenomenon.MOON_EVENT -> "Supermoon"
    Phenomenon.CONJUNCTION -> "Conjunction"
    Phenomenon.TERRESTRIAL -> "Earth event"
}
