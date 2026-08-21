package dev.fritze.skyward

import android.content.Intent
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
import dev.fritze.skyward.alarm.occurrenceIdFromLaunchAction
import dev.fritze.skyward.data.AppContainer
import dev.fritze.skyward.ui.navigation.SkywardNavHost
import kotlinx.coroutines.flow.MutableStateFlow

/** §13: single-Activity, Navigation-Compose (§13.1). */
class MainActivity : ComponentActivity() {

    /**
     * The occurrence a tapped reminder asked for, handed to the NavHost once
     * it is composed. State rather than a plain read of `intent` at startup,
     * because [onNewIntent] can deliver a second tap into an Activity that is
     * already running and already composed.
     */
    private val tappedOccurrenceId = MutableStateFlow<String?>(null)
    private var tapConsumed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // The launching intent is re-delivered on every recreation (rotation,
        // process death), so whether it has already been acted on has to
        // survive with it: otherwise a rotation would yank the user back to
        // the detail screen from wherever they had navigated since.
        tapConsumed = savedInstanceState?.getBoolean(STATE_TAP_CONSUMED) == true
        if (!tapConsumed) tappedOccurrenceId.value = occurrenceIdFromLaunchAction(intent?.action)
        val container = (application as SkywardApplication).container
        setContent {
            val occurrenceId by tappedOccurrenceId.collectAsState()
            SkywardApp(
                container = container,
                tappedOccurrenceId = occurrenceId,
                onTapConsumed = {
                    tapConsumed = true
                    tappedOccurrenceId.value = null
                },
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        deliverNotificationTap(intent)
    }

    /**
     * What [onNewIntent] does with a tap that arrives while the app is already
     * running, and §17.5's seam onto it: an instrumented test cannot drive a
     * real new-intent delivery through `ActivityScenario`, and starting the
     * Activity a second time to provoke one leaves a scenario the test can no
     * longer take to DESTROYED. `launchMode="singleTop"` in the manifest is
     * what routes a real tap through here rather than onto a second copy of
     * the app.
     */
    internal fun deliverNotificationTap(intent: Intent) {
        occurrenceIdFromLaunchAction(intent.action)?.let {
            tapConsumed = false
            tappedOccurrenceId.value = it
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_TAP_CONSUMED, tapConsumed)
    }
}

private const val STATE_TAP_CONSUMED = "notification_tap_consumed"

@Composable
private fun SkywardApp(container: AppContainer, tappedOccurrenceId: String?, onTapConsumed: () -> Unit) {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            val onboardingDone by container.settingsRepo.observeOnboardingDone().collectAsState(initial = null)
            when (onboardingDone) {
                null -> Box(Modifier.fillMaxSize()) // brief loading gate while the flag loads
                else -> SkywardNavHost(container, onboardingDone == true, tappedOccurrenceId, onTapConsumed)
            }
        }
    }
}
