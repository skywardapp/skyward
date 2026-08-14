package dev.fritze.skyward

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import dev.fritze.skyward.ui.navigation.SkywardNavHost

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
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            val onboardingDone by container.settingsRepo.observeOnboardingDone().collectAsState(initial = null)
            when (onboardingDone) {
                null -> Box(Modifier.fillMaxSize()) // brief loading gate while the flag loads
                else -> SkywardNavHost(container, onboardingDone == true)
            }
        }
    }
}
