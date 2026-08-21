package dev.fritze.skyward.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color as AndroidColor
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import dev.fritze.skyward.core.persistence.ThemeChoice

/**
 * §13: "Material 3, Jetpack Compose, dynamic color; […] dark theme
 * default-follows-system."
 *
 * The default matters more here than in most apps: the scenario the app is
 * built for is a user checking aurora or shower conditions outdoors at night,
 * where a full-brightness white screen destroys dark adaptation. So [ThemeChoice.SYSTEM]
 * — the value a user who never opens Settings has — resolves to whatever the
 * OS reports, and the §11 `theme` setting only exists to override that.
 */
@Composable
fun SkywardTheme(theme: ThemeChoice, content: @Composable () -> Unit) {
    val dark = useDarkColors(theme, isSystemInDarkTheme())
    val context = LocalContext.current
    val colorScheme = when {
        // Dynamic color is the §13 default wherever the platform has it (API 31+);
        // below that there is no wallpaper palette to derive from, so the static
        // pair below stands in.
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> FallbackDarkScheme
        else -> FallbackLightScheme
    }

    // Compose paints under the system bars, so bar icon tint and the
    // navigation-bar scrim have to follow *this* scheme. MainActivity's
    // `enableEdgeToEdge()` derives both from the OS night-mode setting, which is
    // right only while the theme is SYSTEM: re-apply them here, keyed on the
    // resolved darkness, so an override doesn't leave white icons on a white
    // scrim. The scrims are androidx's own defaults, restated because
    // `enableEdgeToEdge`'s no-argument form doesn't expose them.
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val activity = view.context.findActivity() as? ComponentActivity ?: return@SideEffect
            activity.enableEdgeToEdge(
                statusBarStyle = SystemBarStyle.auto(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT) { dark },
                navigationBarStyle = SystemBarStyle.auto(NavigationBarLightScrim, NavigationBarDarkScrim) { dark },
            )
        }
    }

    MaterialTheme(colorScheme = colorScheme, content = content)
}

private val NavigationBarLightScrim = AndroidColor.argb(0xe6, 0xFF, 0xFF, 0xFF)
private val NavigationBarDarkScrim = AndroidColor.argb(0x80, 0x1b, 0x1b, 0x1b)

/**
 * Pre-API-31 fallbacks. Deliberately only the few roles that carry the app's
 * identity (Material derives the rest): the desktop app's fuller palette can't
 * be shared because `androidx.compose.ui.graphics.Color` may not enter `:core`
 * (§15.3), and re-stating it here would leave two copies to drift apart.
 */
private val FallbackDarkScheme = darkColorScheme(
    primary = Color(0xFF9FC6FF),
    secondary = Color(0xFF8FD3C7),
    background = Color(0xFF0B0E14),
    surface = Color(0xFF11151E),
)

private val FallbackLightScheme = lightColorScheme(
    primary = Color(0xFF2B4C86),
    secondary = Color(0xFF1F6E60),
)

/**
 * `LocalView.current.context` is not necessarily the Activity -- Compose wraps
 * it in a ContextThemeWrapper -- so unwrap rather than casting and crashing.
 */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
