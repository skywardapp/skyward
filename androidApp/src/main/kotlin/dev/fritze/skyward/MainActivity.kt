package dev.fritze.skyward

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import dev.fritze.skyward.core.persistence.ThemeChoice
import dev.fritze.skyward.ui.navigation.SkywardNavHost
import dev.fritze.skyward.ui.theme.SkywardTheme

/** §13: single-Activity, Navigation-Compose (§13.1). */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as SkywardApplication).container
        setContent {
            SkywardApp(container = container)
        }
    }
}

@Composable
private fun SkywardApp(container: dev.fritze.skyward.data.AppContainer) {
    val onboardingDone by container.settingsRepo.observeOnboardingDone().collectAsState(initial = null)
    // Both settings come off the same background-dispatcher database read, so
    // the gate below already waits for the theme too; rendering the loading Box
    // under a not-yet-known theme would flash the wrong background at exactly
    // the user this fixes (§13). The window background carries that first frame
    // instead -- see res/values-night/themes.xml.
    val theme by container.settingsRepo.observeTheme().collectAsState(initial = null)

    SkywardTheme(theme ?: ThemeChoice.SYSTEM) {
        Surface(modifier = Modifier.fillMaxSize()) {
            when {
                theme == null || onboardingDone == null -> Box(Modifier.fillMaxSize()) // brief loading gate while the flags load
                else -> SkywardNavHost(container, onboardingDone == true)
            }
        }
    }
}
