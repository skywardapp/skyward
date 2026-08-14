package dev.fritze.skyward.ui.onboarding

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.fritze.skyward.data.AppContainer
import dev.fritze.skyward.ui.common.rememberLocationPermissionRequester
import kotlinx.coroutines.launch

private enum class OnboardingStep { WELCOME, LOCATION, NOTIFICATIONS, EXACT_ALARM, RULES_PREVIEW }

/** §13.1's first-run flow: welcome -> add first location (disclosure before the coarse-location prompt, §10.2) -> notification permission -> exact-alarm explainer -> default rules preview. */
@Composable
fun OnboardingScreen(container: AppContainer, onDone: () -> Unit) {
    val viewModel: OnboardingViewModel = viewModel { OnboardingViewModel(container) }
    var step by remember { mutableStateOf(OnboardingStep.WELCOME) }
    val coroutineScope = rememberCoroutineScope()

    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            when (step) {
                OnboardingStep.WELCOME -> WelcomeStep { step = OnboardingStep.LOCATION }
                OnboardingStep.LOCATION -> LocationStep(viewModel, onNext = { step = OnboardingStep.NOTIFICATIONS })
                OnboardingStep.NOTIFICATIONS -> NotificationsStep(onNext = { step = OnboardingStep.EXACT_ALARM })
                OnboardingStep.EXACT_ALARM -> ExactAlarmStep(onNext = { step = OnboardingStep.RULES_PREVIEW })
                OnboardingStep.RULES_PREVIEW -> RulesPreviewStep(viewModel) {
                    // coroutineScope (tied to this composition, not the ViewModel) so finish()'s
                    // work is awaited in full before onDone() pops Routes.ONBOARDING -- otherwise
                    // the navigation itself would race the write it's meant to wait for.
                    coroutineScope.launch {
                        viewModel.finish()
                        onDone()
                    }
                }
            }
        }
    }
}

@Composable
private fun WelcomeStep(onNext: () -> Unit) {
    Text("Welcome to Skyward", style = MaterialTheme.typography.headlineMedium)
    Text("Reminders for eclipses, meteor showers, aurora, comets, and more — only for the ones you can actually see.")
    Button(onClick = onNext) { Text("Get started") }
}

@Composable
private fun LocationStep(viewModel: OnboardingViewModel, onNext: () -> Unit) {
    var name by remember { mutableStateOf("Home") }
    var lat by remember { mutableStateOf("") }
    var lon by remember { mutableStateOf("") }

    val requestLocation = rememberLocationPermissionRequester { point ->
        if (point != null) {
            lat = point.latDeg.toString()
            lon = point.lonDeg.toString()
        }
    }

    Text("Add your first location", style = MaterialTheme.typography.headlineSmall)
    Text("Skyward computes visibility from wherever you tell it — manual entry works fine, no permission required.")
    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(value = lat, onValueChange = { lat = it }, label = { Text("Latitude") }, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(value = lon, onValueChange = { lon = it }, label = { Text("Longitude") }, modifier = Modifier.fillMaxWidth())
    OutlinedButton(onClick = requestLocation) { Text("Use current location") }

    val latValue = lat.toDoubleOrNull()
    val lonValue = lon.toDoubleOrNull()
    Button(
        onClick = {
            if (latValue != null && lonValue != null) viewModel.addFirstLocation(name.ifBlank { "Home" }, dev.fritze.skyward.core.model.GeoPoint(latValue, lonValue))
            onNext()
        },
        enabled = latValue != null && lonValue != null,
    ) { Text("Continue") }
    TextButton(onClick = onNext) { Text("Skip for now") }
}

@Composable
private fun NotificationsStep(onNext: () -> Unit) {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { onNext() }
    val alreadyGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    Text("Stay in the loop", style = MaterialTheme.typography.headlineSmall)
    Text("Skyward needs permission to show notifications — that's the whole point.")
    if (alreadyGranted) {
        Button(onClick = onNext) { Text("Continue") }
    } else {
        Button(onClick = { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text("Allow notifications") }
        TextButton(onClick = onNext) { Text("Not now") }
    }
}

@Composable
private fun ExactAlarmStep(onNext: () -> Unit) {
    val context = LocalContext.current
    Text("Precise timing", style = MaterialTheme.typography.headlineSmall)
    Text(
        "For reminders to fire at the exact minute, Skyward needs the exact-alarm permission. Without it, " +
            "reminders still work but may arrive a little late. You can change this later in Settings.",
    )
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        Button(onClick = {
            context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")))
        }) { Text("Enable exact alarms") }
    }
    TextButton(onClick = onNext) { Text(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) "Not now" else "Continue") }
}

@Composable
private fun RulesPreviewStep(viewModel: OnboardingViewModel, onFinish: () -> Unit) {
    val rules by viewModel.defaultRulePreview.collectAsState()
    Text("Default reminders", style = MaterialTheme.typography.headlineSmall)
    Text("Skyward ships with a sensible starting set — edit or turn any of these off anytime in Rules.")
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (rule in rules) Text("• ${rule.name}")
    }
    Button(onClick = onFinish) { Text("Start using Skyward") }
}
