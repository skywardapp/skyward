package dev.fritze.skyward.ui.theme

import dev.fritze.skyward.core.persistence.ThemeChoice

/**
 * §13's "dark theme default-follows-system", as a pure function.
 *
 * It lives in its own file rather than beside [SkywardTheme] so a JVM unit
 * test can reach it: SkywardTheme.kt's file-level properties initialise
 * `android.graphics` and Compose colour values, which the unmocked
 * `android.jar` on the unit-test classpath throws from, taking the whole file
 * facade class down with them.
 */
internal fun useDarkColors(theme: ThemeChoice, systemInDarkTheme: Boolean): Boolean = when (theme) {
    ThemeChoice.SYSTEM -> systemInDarkTheme
    ThemeChoice.DARK -> true
    ThemeChoice.LIGHT -> false
}
