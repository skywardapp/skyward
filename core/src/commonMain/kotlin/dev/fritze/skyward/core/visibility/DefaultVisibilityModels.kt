package dev.fritze.skyward.core.visibility

import dev.fritze.skyward.core.model.Phenomenon

/**
 * §4.1: the phenomenon -> model map every frontend wires up identically
 * -- shared here so `AppContainer` and `DesktopContainer` build one set
 * of instances rather than each independently constructing the same map.
 */
val defaultVisibilityModels: Map<Phenomenon, VisibilityModel> = mapOf(
    Phenomenon.SOLAR_ECLIPSE to SolarEclipseVisibilityModel(),
    Phenomenon.LUNAR_ECLIPSE to LunarEclipseVisibilityModel(),
    Phenomenon.AURORA to AuroraVisibilityModel(),
    Phenomenon.METEOR_SHOWER to MeteorShowerVisibilityModel(),
    Phenomenon.COMET to CometVisibilityModel(),
    Phenomenon.MOON_EVENT to MoonEventVisibilityModel(),
    Phenomenon.CONJUNCTION to ConjunctionVisibilityModel(),
    Phenomenon.TERRESTRIAL to TerrestrialVisibilityModel(),
)
