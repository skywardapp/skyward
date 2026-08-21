package dev.fritze.skyward.core.persistence

/**
 * §11's `theme` setting value. It lives in `:core` rather than in either
 * frontend because §12's export/import carries the raw settings map between
 * platforms — a `theme` written by the desktop app must mean the same thing
 * when Android reads it back.
 *
 * [SYSTEM] is the *default*, not a third palette, and the two frontends
 * resolve it differently because their platforms differ: Android follows the
 * OS dark-mode signal (§13, "dark theme default-follows-system"), while
 * desktop has no reliable cross-DE equivalent and resolves it to dark. The
 * schemes themselves stay per-frontend: `androidx.compose.ui.graphics.Color`
 * may not enter `:core` (§15.3).
 */
enum class ThemeChoice {
    SYSTEM,
    DARK,
    LIGHT,
    ;

    companion object {
        /** Tolerant of case so a hand-edited settings file or an older writer still parses; anything unrecognised falls back to [SYSTEM]. */
        fun parse(raw: String?): ThemeChoice = entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: SYSTEM
    }
}
