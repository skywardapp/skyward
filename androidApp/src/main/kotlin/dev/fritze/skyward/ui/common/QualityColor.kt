package dev.fritze.skyward.ui.common

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import dev.fritze.skyward.core.model.Quality

/**
 * The quality *ramp*, as distinct from `core/format`'s `qualityLabel`: the
 * words are shared with the desktop app, the colours can't be —
 * `androidx.compose.ui.graphics.Color` may not enter `:core` (§15.3), and
 * these resolve against the Material theme rather than a fixed palette.
 */
@Composable
@ReadOnlyComposable
fun qualityColor(quality: Quality): Color = when (quality) {
    Quality.EXCELLENT -> MaterialTheme.colorScheme.primary
    Quality.GOOD -> MaterialTheme.colorScheme.tertiary
    Quality.MARGINAL -> MaterialTheme.colorScheme.secondary
    Quality.NONE -> MaterialTheme.colorScheme.onSurfaceVariant
}
