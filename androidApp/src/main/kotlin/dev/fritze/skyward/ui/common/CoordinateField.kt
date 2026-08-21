package dev.fritze.skyward.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import dev.fritze.skyward.core.format.CoordinateAxis
import dev.fritze.skyward.core.format.CoordinateEntry
import dev.fritze.skyward.core.format.flipCoordinateSign
import dev.fritze.skyward.core.format.parseCoordinate

/**
 * One latitude or longitude field: numeric keyboard, the range message from
 * [dev.fritze.skyward.core.format.parseCoordinate] shown inline, and a
 * hemisphere button that flips the sign.
 *
 * With §13.1's search box absent (no geocoding source is defined in §7),
 * typing coordinates is the primary way a location gets created, so the field
 * has to say what it wants instead of leaving Save silently greyed out.
 *
 * The hemisphere button is not decoration: Compose has no signed-decimal
 * [KeyboardType], and the IME behind `Decimal` is free to offer a keypad with
 * no minus key at all -- without the button, the entire southern and western
 * hemispheres could be untypeable on some devices.
 */
@Composable
fun CoordinateField(
    value: String,
    entry: CoordinateEntry,
    axis: CoordinateAxis,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    imeAction: ImeAction = ImeAction.Next,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(axis.label) },
        isError = entry.isError,
        supportingText = { Text(entry.error ?: axis.hint) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = imeAction),
        trailingIcon = {
            if (entry.degrees != null) {
                // Labelled from the typed text, not the parsed Double: "-0"
                // parses to -0.0, which compares equal to zero, so a
                // value-based label would read "N" for a value the user has
                // already signed and the button would then unsign it.
                TextButton(onClick = { onValueChange(flipCoordinateSign(value)) }) { Text(axis.hemisphere(value)) }
            }
        },
        modifier = modifier,
    )
}

private val CoordinateAxis.label: String
    get() = when (this) {
        CoordinateAxis.LATITUDE -> "Latitude"
        CoordinateAxis.LONGITUDE -> "Longitude"
    }

/**
 * Shown while the field is valid or empty, in place of the error -- the
 * format is easier to copy than to describe.
 */
private val CoordinateAxis.hint: String
    get() = when (this) {
        CoordinateAxis.LATITUDE -> "Decimal degrees, negative south of the equator"
        CoordinateAxis.LONGITUDE -> "Decimal degrees, negative west of Greenwich"
    }

private fun CoordinateAxis.hemisphere(text: String): String {
    val negative = text.trim().startsWith("-")
    return when (this) {
        CoordinateAxis.LATITUDE -> if (negative) "S" else "N"
        CoordinateAxis.LONGITUDE -> if (negative) "W" else "E"
    }
}

/**
 * The whole "where is this?" block: both coordinate fields, the "use current
 * location" button, and whatever that button has to report when it doesn't
 * produce a fix.
 *
 * One composable rather than two copies, because onboarding and the location
 * editor ask the same question and had drifted into answering it with
 * near-identical code -- the §10.2 disclosure flow is the same, the outcome
 * handling is the same, and only the surrounding form differs.
 *
 * The coordinates stay hoisted (the callers own them: onboarding hands them
 * to `addFirstLocation`, the editor to `save`), but the fix-failure message
 * lives here -- no caller has anything to do with it beyond showing it.
 */
@Composable
fun CoordinateEntrySection(
    latText: String,
    lonText: String,
    onLatChange: (String) -> Unit,
    onLonChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var fixFailure by remember { mutableStateOf<String?>(null) }
    val requestLocation = rememberLocationPermissionRequester { outcome ->
        fixFailure = outcome.failureMessage
        if (outcome is LocationFixOutcome.Fixed) {
            onLatChange(outcome.point.latDeg.toString())
            onLonChange(outcome.point.lonDeg.toString())
        }
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CoordinateField(
            value = latText,
            entry = parseCoordinate(latText, CoordinateAxis.LATITUDE),
            axis = CoordinateAxis.LATITUDE,
            onValueChange = onLatChange,
            modifier = Modifier.fillMaxWidth(),
        )
        CoordinateField(
            value = lonText,
            entry = parseCoordinate(lonText, CoordinateAxis.LONGITUDE),
            axis = CoordinateAxis.LONGITUDE,
            onValueChange = onLonChange,
            modifier = Modifier.fillMaxWidth(),
            imeAction = ImeAction.Done,
        )
        OutlinedButton(onClick = requestLocation) { Text("Use current location") }
        fixFailure?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
}
