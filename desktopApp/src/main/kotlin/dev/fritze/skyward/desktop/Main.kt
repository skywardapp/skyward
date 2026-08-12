package dev.fritze.skyward.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState

/**
 * M0 acceptance check (§18): a hello-world Compose for Desktop app must boot.
 * The real nav rail / views (§14) land in M6.
 */
fun main() = application {
    val windowState = rememberWindowState(
        position = WindowPosition.Aligned(Alignment.Center),
        size = DpSize(1280.dp, 800.dp),
    )
    Window(onCloseRequest = ::exitApplication, title = "Skyward", state = windowState) {
        SkywardDesktopApp()
    }
}

@Composable
private fun SkywardDesktopApp() {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Skyward")
            }
        }
    }
}
