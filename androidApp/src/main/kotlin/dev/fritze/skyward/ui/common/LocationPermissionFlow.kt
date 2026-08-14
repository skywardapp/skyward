package dev.fritze.skyward.ui.common

import android.Manifest
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.SystemClock
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

/** getLastKnownLocation() can return "quite old" cached fixes (its own docs say to always check age); reject anything older than this. */
private val MAX_LOCATION_AGE_MILLIS = 30 * 60 * 1000L

private fun readLastKnownCoarseLocation(context: android.content.Context): GeoPoint? {
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) return null
    val manager = context.getSystemService(android.content.Context.LOCATION_SERVICE) as? LocationManager ?: return null
    val providers = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER, LocationManager.GPS_PROVIDER)
    // Collect every enabled provider's cached fix and pick the newest by elapsed-realtime (not
    // Location.getTime()/wall-clock, which isn't monotonic) -- the first enabled provider isn't
    // necessarily the freshest one.
    val newest = providers
        .filter { manager.isProviderEnabled(it) }
        .mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
        .maxByOrNull(Location::getElapsedRealtimeNanos)
        ?: return null
    val ageMillis = (SystemClock.elapsedRealtimeNanos() - newest.elapsedRealtimeNanos) / 1_000_000
    if (ageMillis > MAX_LOCATION_AGE_MILLIS) return null
    return GeoPoint(newest.latitude, newest.longitude)
}
