package dev.fritze.skyward.ui.common

import dev.fritze.skyward.core.model.Phenomenon

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
