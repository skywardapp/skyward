package dev.fritze.skyward.ui.common

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import dev.fritze.skyward.core.format.CoordinateAxis
import dev.fritze.skyward.core.format.CoordinateEntry
import dev.fritze.skyward.core.format.flipCoordinateSign

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
            val degrees = entry.degrees
            if (degrees != null) {
                TextButton(onClick = { onValueChange(flipCoordinateSign(value)) }) { Text(axis.hemisphere(degrees)) }
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

/** Shown while the field is valid or empty, in place of the error -- the format is easier to copy than to describe. */
private val CoordinateAxis.hint: String
    get() = when (this) {
        CoordinateAxis.LATITUDE -> "Decimal degrees, negative south of the equator"
        CoordinateAxis.LONGITUDE -> "Decimal degrees, negative west of Greenwich"
    }

private fun CoordinateAxis.hemisphere(degrees: Double): String = when (this) {
    CoordinateAxis.LATITUDE -> if (degrees < 0) "S" else "N"
    CoordinateAxis.LONGITUDE -> if (degrees < 0) "W" else "E"
}
