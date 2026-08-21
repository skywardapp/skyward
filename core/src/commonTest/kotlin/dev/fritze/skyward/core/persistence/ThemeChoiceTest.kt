package dev.fritze.skyward.core.persistence

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * §11's `theme` value crosses process and platform boundaries -- §12's
 * export/import moves it between the desktop and Android apps -- so what an
 * unrecognised or absent value means is behaviour, not a detail.
 *
 * These cover [ThemeChoice.parse] alone. The trip through storage -- that
 * `setTheme` writes what `observeTheme` and the desktop settings screen read
 * back -- is covered by RepositoriesTest in desktopTest, where a driver to
 * build a real database exists.
 */
class ThemeChoiceTest {

    @Test
    fun everyChoiceParsesBackFromTheNameItIsStoredUnder() {
        for (choice in ThemeChoice.entries) {
            assertEquals(choice, ThemeChoice.parse(choice.name), "round trip of $choice")
        }
    }

    @Test
    fun anAbsentValueParsesAsFollowTheSystem() {
        // The default for a user who has never opened the theme setting, and
        // the reason §13's "dark theme default-follows-system" holds on a
        // fresh install: no row in app_setting means SYSTEM, not LIGHT.
        assertEquals(ThemeChoice.SYSTEM, ThemeChoice.parse(null))
    }

    @Test
    fun anUnrecognisedValueParsesAsFollowTheSystemRatherThanThrowing() {
        // A settings file written by a future version, or edited by hand.
        assertEquals(ThemeChoice.SYSTEM, ThemeChoice.parse("midnight"))
        assertEquals(ThemeChoice.SYSTEM, ThemeChoice.parse(""))
    }

    @Test
    fun caseDoesNotDecideTheTheme() {
        // The desktop settings screen renders the choices lowercased; nothing
        // stops a hand-written or older file from storing them that way too.
        assertEquals(ThemeChoice.DARK, ThemeChoice.parse("dark"))
        assertEquals(ThemeChoice.LIGHT, ThemeChoice.parse("Light"))
    }
}
