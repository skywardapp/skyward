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
 * What came back from a "use current location" tap. Every way of not getting
 * a fix used to collapse into a single `null`, which left the caller with
 * nothing to say -- so the button did nothing visible after the user had just
 * answered a system dialog. The two failures need different remedies (grant
 * the permission vs. wait for a fix or type the coordinates), so they stay
 * apart all the way to the screen.
 */
sealed interface LocationFixOutcome {
    /** A cached coarse fix, recent enough to stand for "where the user is now". */
    data class Fixed(val point: GeoPoint) : LocationFixOutcome

    /**
     * The runtime prompt came back denied, or the permission was revoked
     * between the check and the read.
     */
    data object PermissionDenied : LocationFixOutcome

    /**
     * Permission is granted, but no enabled provider holds a fix younger than
     * [MAX_LOCATION_AGE_MILLIS].
     */
    data object NoRecentFix : LocationFixOutcome
}

/**
 * The sentence to show under the button, or null when there is nothing to
 * report. Both screens show the same wording for the same outcome -- the
 * remedy ("type the coordinates") is the same in either place, and it is the
 * cause that differs.
 */
val LocationFixOutcome.failureMessage: String?
    get() = when (this) {
        is LocationFixOutcome.Fixed -> null
        LocationFixOutcome.PermissionDenied ->
            "Location permission denied — enter the coordinates below instead."
        LocationFixOutcome.NoRecentFix ->
            "Couldn't get a location fix — enter the coordinates below instead."
    }

/**
 * §10.2's "location prominent disclosure (required by Play, harmless on
 * F-Droid): before the first ACCESS_COARSE_LOCATION runtime prompt, show a
 * full-screen disclosure ... on-device only and never transmitted." This is
 * the dialog-sized version of that disclosure, shared by onboarding and the
 * LocationEditor's "use current location" button -- both are places the
 * very first prompt could happen depending on what the user skips.
 *
 * [onOutcome] fires for every tap that reaches the system, including the ones
 * that fail. Dismissing the disclosure itself with "Not now" is deliberately
 * silent: the user withdrew the request, so there is no failure to report.
 */
@Composable
fun rememberLocationPermissionRequester(onOutcome: (LocationFixOutcome) -> Unit): () -> Unit {
    val context = LocalContext.current
    var showDisclosure by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        onOutcome(if (granted) readLastKnownCoarseLocation(context) else LocationFixOutcome.PermissionDenied)
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
            onOutcome(readLastKnownCoarseLocation(context))
        } else {
            showDisclosure = true
        }
    }
}

/** getLastKnownLocation() can return "quite old" cached fixes (its own docs say to always check age); reject anything older than this. */
private val MAX_LOCATION_AGE_MILLIS = 30 * 60 * 1000L

private fun readLastKnownCoarseLocation(context: android.content.Context): LocationFixOutcome {
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
        return LocationFixOutcome.PermissionDenied
    }
    // A missing LocationManager is indistinguishable to the user from a device
    // that simply has no fix, and there is no permission for them to fix, so it
    // reports as NoRecentFix rather than growing a third message.
    val manager = context.getSystemService(android.content.Context.LOCATION_SERVICE) as? LocationManager
        ?: return LocationFixOutcome.NoRecentFix
    val providers = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER, LocationManager.GPS_PROVIDER)
    // Collect every enabled provider's cached fix and pick the newest by elapsed-realtime (not
    // Location.getTime()/wall-clock, which isn't monotonic) -- the first enabled provider isn't
    // necessarily the freshest one.
    val newest = providers
        .filter { manager.isProviderEnabled(it) }
        .mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
        .maxByOrNull(Location::getElapsedRealtimeNanos)
        ?: return LocationFixOutcome.NoRecentFix
    val ageMillis = (SystemClock.elapsedRealtimeNanos() - newest.elapsedRealtimeNanos) / 1_000_000
    if (ageMillis > MAX_LOCATION_AGE_MILLIS) return LocationFixOutcome.NoRecentFix
    return newest.toGeoPoint()?.let(LocationFixOutcome::Fixed) ?: LocationFixOutcome.NoRecentFix
}

/**
 * A provider fix as a §5 [GeoPoint], or null if it isn't one.
 *
 * `Location` documents longitude as `[-180, 180]` inclusive while [GeoPoint]
 * is `[-180, 180)`, so a fix on the antimeridian has to be folded onto the
 * `-180` end rather than stored as the other number for the same meridian.
 * Everything else out of range, or non-finite, is a fix the app cannot use --
 * reported as "no fix" rather than saved, because it is not one.
 */
private fun Location.toGeoPoint(): GeoPoint? {
    if (!latitude.isFinite() || !longitude.isFinite()) return null
    if (latitude !in -90.0..90.0 || longitude < -180.0 || longitude > 180.0) return null
    return GeoPoint(latitude, if (longitude == 180.0) -180.0 else longitude)
}
