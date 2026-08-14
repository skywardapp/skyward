package dev.fritze.skyward.ui.common

import android.Manifest
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import dev.fritze.skyward.core.model.GeoPoint

/**
 * §10.2's "location prominent disclosure (required by Play, harmless on
 * F-Droid): before the first ACCESS_COARSE_LOCATION runtime prompt, show a
 * full-screen disclosure ... on-device only and never transmitted." This is
 * the dialog-sized version of that disclosure, shared by onboarding and the
 * LocationEditor's "use current location" button -- both are places the
 * very first prompt could happen depending on what the user skips.
 */
@Composable
fun rememberLocationPermissionRequester(onLocation: (GeoPoint?) -> Unit): () -> Unit {
    val context = LocalContext.current
    var showDisclosure by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        onLocation(if (granted) readLastKnownCoarseLocation(context) else null)
    }

    if (showDisclosure) {
        AlertDialog(
            onDismissRequest = { showDisclosure = false },
            title = { Text("Use your location?") },
            text = {
                Text(
                    "Skyward can use your approximate location to compute what's visible from where you " +
                        "are. This is entirely optional -- you can always add locations manually instead. " +
                        "Your location is used on-device only and never transmitted anywhere.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showDisclosure = false
                    permissionLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
                }) { Text("Continue") }
            },
            dismissButton = { TextButton(onClick = { showDisclosure = false }) { Text("Not now") } },
        )
    }

    return {
        val alreadyGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (alreadyGranted) {
            onLocation(readLastKnownCoarseLocation(context))
        } else {
            showDisclosure = true
        }
    }
}

private fun readLastKnownCoarseLocation(context: android.content.Context): GeoPoint? {
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) return null
    val manager = context.getSystemService(android.content.Context.LOCATION_SERVICE) as? LocationManager ?: return null
    val providers = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER, LocationManager.GPS_PROVIDER)
    for (provider in providers) {
        if (!manager.isProviderEnabled(provider)) continue
        val location = runCatching { manager.getLastKnownLocation(provider) }.getOrNull() ?: continue
        return GeoPoint(location.latitude, location.longitude)
    }
    return null
}
