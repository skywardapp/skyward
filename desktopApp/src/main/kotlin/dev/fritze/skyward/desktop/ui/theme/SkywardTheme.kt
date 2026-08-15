package dev.fritze.skyward.desktop.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import dev.fritze.skyward.core.model.Phenomenon
import dev.fritze.skyward.core.model.Quality

/**
 * §11's `theme` setting. Desktop has no reliable cross-DE "follow the system
 * theme" signal, so [SYSTEM] resolves to dark — an app whose whole subject is
 * the night sky is a reasonable place for that to be the default rather than
 * a light-mode surprise.
 */
enum class ThemeChoice { SYSTEM, DARK, LIGHT;

    companion object {
        fun parse(raw: String?): ThemeChoice = entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: SYSTEM
    }
}

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF9FC6FF),
    onPrimary = Color(0xFF0A1B33),
    primaryContainer = Color(0xFF1D3557),
    onPrimaryContainer = Color(0xFFD6E4FF),
    secondary = Color(0xFF8FD3C7),
    background = Color(0xFF0B0E14),
    onBackground = Color(0xFFE3E6EC),
    surface = Color(0xFF11151E),
    onSurface = Color(0xFFE3E6EC),
    surfaceVariant = Color(0xFF1B2130),
    onSurfaceVariant = Color(0xFFB6BDCB),
    outline = Color(0xFF4B5567),
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF2B4C86),
    secondary = Color(0xFF1F6E60),
)

@Composable
fun SkywardTheme(theme: ThemeChoice, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (theme == ThemeChoice.LIGHT) LightScheme else DarkScheme,
        content = content,
    )
}

/**
 * §4.1's escape hatch used deliberately: "colors for quality levels" is
 * named there as the kind of presentational helper that may be shared — but
 * as *pure functions*, and `androidx.compose.ui.graphics.Color` may not
 * enter `:core` (§15.3). So the ramp lives here, and Android keeps its own.
 */
fun qualityColor(quality: Quality): Color = when (quality) {
    Quality.NONE -> Color(0xFF5A6377)
    Quality.MARGINAL -> Color(0xFFD8A657)
    Quality.GOOD -> Color(0xFF6FB2E8)
    Quality.EXCELLENT -> Color(0xFF6FE3A8)
}

fun qualityLabel(quality: Quality): String = when (quality) {
    Quality.NONE -> "Not visible"
    Quality.MARGINAL -> "Marginal"
    Quality.GOOD -> "Good"
    Quality.EXCELLENT -> "Excellent"
}

/** Lane colors for the timeline (§14.2) and layer colors on the map (§14.1). */
fun phenomenonColor(phenomenon: Phenomenon): Color = when (phenomenon) {
    Phenomenon.SOLAR_ECLIPSE -> Color(0xFFFFC65C)
    Phenomenon.LUNAR_ECLIPSE -> Color(0xFFD08BE0)
    Phenomenon.AURORA -> Color(0xFF5FE3B0)
    Phenomenon.METEOR_SHOWER -> Color(0xFF7FB4FF)
    Phenomenon.COMET -> Color(0xFF9FE8E0)
    Phenomenon.MOON_EVENT -> Color(0xFFDCDCE6)
    Phenomenon.CONJUNCTION -> Color(0xFFF2907A)
    Phenomenon.TERRESTRIAL -> Color(0xFFE0705F)
}

/** §14.4's Kp classes, colored by NOAA's own G-scale bands. */
fun kpColor(kp: Double): Color = when {
    kp < 4.0 -> Color(0xFF4F8A5B)
    kp < 5.0 -> Color(0xFFB7C24A)
    kp < 6.0 -> Color(0xFFE0B33C)
    kp < 7.0 -> Color(0xFFE08A3C)
    kp < 8.0 -> Color(0xFFE05C3C)
    else -> Color(0xFFD03A6B)
}

/** NOAA G-scale label for a Kp value (G1 starts at Kp 5). */
fun gScaleLabel(kp: Double): String? = when {
    kp < 5.0 -> null
    kp < 6.0 -> "G1"
    kp < 7.0 -> "G2"
    kp < 8.0 -> "G3"
    kp < 9.0 -> "G4"
    else -> "G5"
}
