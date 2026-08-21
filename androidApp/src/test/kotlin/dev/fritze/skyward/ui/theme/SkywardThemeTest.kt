package dev.fritze.skyward.ui.theme

import dev.fritze.skyward.core.persistence.ThemeChoice
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * §13: "dark theme default-follows-system". The regression this guards is the
 * one the app shipped with -- a static light scheme handed to a user checking
 * aurora conditions outdoors at night -- so the case that matters most is
 * SYSTEM + system-in-dark.
 */
class SkywardThemeTest {

    @Test
    fun theDefaultFollowsTheSystem() {
        assertTrue(useDarkColors(ThemeChoice.SYSTEM, systemInDarkTheme = true))
        assertFalse(useDarkColors(ThemeChoice.SYSTEM, systemInDarkTheme = false))
    }

    @Test
    fun anExplicitChoiceOverridesTheSystem() {
        // Both directions: §11's setting is an override, not a hint, so a user
        // who picked DARK keeps it on a light-mode phone and vice versa.
        assertTrue(useDarkColors(ThemeChoice.DARK, systemInDarkTheme = false))
        assertFalse(useDarkColors(ThemeChoice.LIGHT, systemInDarkTheme = true))
    }
}
