package dev.fritze.skyward.desktop.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import dev.fritze.skyward.core.model.Phenomenon
import dev.fritze.skyward.core.model.Quality
import dev.fritze.skyward.core.persistence.ThemeChoice

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

/**
 * Defined in full, deliberately. It used to name only `primary` and
 * `secondary`, which left every other role at Material's baseline — a purple
 * scheme underneath two blue accents, on a surface the rest of the app was
 * never designed against (#79).
 */
private val LightScheme = lightColorScheme(
    primary = Color(0xFF2B4C86),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD6E2FA),
    onPrimaryContainer = Color(0xFF0A1B33),
    secondary = Color(0xFF1F6E60),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCFE9E2),
    onSecondaryContainer = Color(0xFF07231D),
    background = Color(0xFFF7F9FC),
    onBackground = Color(0xFF161A21),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF161A21),
    surfaceVariant = Color(0xFFE4E9F2),
    onSurfaceVariant = Color(0xFF474E5C),
    outline = Color(0xFF757D8C),
    error = Color(0xFFA3132A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD9),
    onErrorContainer = Color(0xFF410004),
)

/**
 * §4.1's escape hatch used deliberately: "colors for quality levels" is
 * named there as the kind of presentational helper that may be shared — but
 * as *pure functions*, and `androidx.compose.ui.graphics.Color` may not
 * enter `:core` (§15.3). So the ramps live here, and Android keeps its own.
 *
 * Two instances rather than one set of constants, because these are used as
 * **text and small-mark colors on the app's own surface**
 * (`OverviewScreen`'s quality line, `EventDetailPane`'s, the aurora Kp
 * headline). The dark ramp is pale by design and unreadable on a light
 * background; the light ramp is the same hue at a luminance that reads
 * against white (#79).
 *
 * Canvases that paint their own dark ground — the map's ocean, the sky
 * chart's twilight gradient, the aurora polar plot, the timeline's lane
 * bands — are *not* theme-following surfaces, any more than a photograph is.
 * Those ask for [Dark] explicitly rather than reading the local.
 */
@Immutable
class SkywardPalette private constructor(private val light: Boolean) {

    fun quality(quality: Quality): Color = when (quality) {
        Quality.NONE -> pick(dark = 0xFF5A6377, light = 0xFF4A5262)
        Quality.MARGINAL -> pick(dark = 0xFFD8A657, light = 0xFF8A5A00)
        Quality.GOOD -> pick(dark = 0xFF6FB2E8, light = 0xFF15558F)
        Quality.EXCELLENT -> pick(dark = 0xFF6FE3A8, light = 0xFF11694A)
    }

    /** Lane colors for the timeline (§14.2) and layer colors on the map (§14.1). */
    fun phenomenon(phenomenon: Phenomenon): Color = when (phenomenon) {
        Phenomenon.SOLAR_ECLIPSE -> pick(dark = 0xFFFFC65C, light = 0xFF8A6100)
        Phenomenon.LUNAR_ECLIPSE -> pick(dark = 0xFFD08BE0, light = 0xFF7B3E92)
        Phenomenon.AURORA -> pick(dark = 0xFF5FE3B0, light = 0xFF0F6B4F)
        Phenomenon.METEOR_SHOWER -> pick(dark = 0xFF7FB4FF, light = 0xFF215394)
        Phenomenon.COMET -> pick(dark = 0xFF9FE8E0, light = 0xFF1C6E6B)
        Phenomenon.MOON_EVENT -> pick(dark = 0xFFDCDCE6, light = 0xFF5A5E6B)
        Phenomenon.CONJUNCTION -> pick(dark = 0xFFF2907A, light = 0xFFA3462B)
        Phenomenon.TERRESTRIAL -> pick(dark = 0xFFE0705F, light = 0xFFA32E1E)
    }

    /** §14.4's Kp classes, colored by NOAA's own G-scale bands. */
    fun kp(kp: Double): Color = when {
        kp < 4.0 -> pick(dark = 0xFF4F8A5B, light = 0xFF2F6B3D)
        kp < 5.0 -> pick(dark = 0xFFB7C24A, light = 0xFF66701A)
        kp < 6.0 -> pick(dark = 0xFFE0B33C, light = 0xFF8A6200)
        kp < 7.0 -> pick(dark = 0xFFE08A3C, light = 0xFF8F4C0F)
        kp < 8.0 -> pick(dark = 0xFFE05C3C, light = 0xFF9E2F16)
        else -> pick(dark = 0xFFD03A6B, light = 0xFF8C1244)
    }

    private fun pick(dark: Long, light: Long) = Color(if (this.light) light else dark)

    companion object {
        val Dark = SkywardPalette(light = false)
        val Light = SkywardPalette(light = true)
    }
}

/**
 * Defaults to [SkywardPalette.Dark] so a composable rendered outside
 * [SkywardTheme] (a preview, a test harness) gets the palette the app
 * usually runs in rather than one that silently disagrees with its surface.
 */
val LocalSkywardPalette = staticCompositionLocalOf { SkywardPalette.Dark }

/**
 * §11's `theme` setting. Desktop has no reliable cross-DE "follow the system
 * theme" signal, so [ThemeChoice.SYSTEM] resolves to dark — an app whose whole
 * subject is the night sky is a reasonable place for that to be the default
 * rather than a light-mode surprise. Android resolves the same value against
 * the OS signal instead (§13).
 */
@Composable
fun SkywardTheme(theme: ThemeChoice, content: @Composable () -> Unit) {
    val light = theme == ThemeChoice.LIGHT
    CompositionLocalProvider(LocalSkywardPalette provides if (light) SkywardPalette.Light else SkywardPalette.Dark) {
        MaterialTheme(
            colorScheme = if (light) LightScheme else DarkScheme,
            content = content,
        )
    }
}

@Composable
@ReadOnlyComposable
fun qualityColor(quality: Quality): Color = LocalSkywardPalette.current.quality(quality)

@Composable
@ReadOnlyComposable
fun phenomenonColor(phenomenon: Phenomenon): Color = LocalSkywardPalette.current.phenomenon(phenomenon)

@Composable
@ReadOnlyComposable
fun kpColor(kp: Double): Color = LocalSkywardPalette.current.kp(kp)

/** NOAA G-scale label for a Kp value (G1 starts at Kp 5). */
fun gScaleLabel(kp: Double): String? = when {
    kp < 5.0 -> null
    kp < 6.0 -> "G1"
    kp < 7.0 -> "G2"
    kp < 8.0 -> "G3"
    kp < 9.0 -> "G4"
    else -> "G5"
}
