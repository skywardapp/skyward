package dev.fritze.skyward.core.sync

import dev.fritze.skyward.core.model.SavedLocation
import dev.fritze.skyward.core.rules.Rule
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/**
 * §12.2 file format. What syncs (§12.1): locations, rules (including hidden
 * system rules — §9.1 says those are "included in evaluation & sync"),
 * app settings, per-source settings (folded into [settings], §11's
 * `source.<id>.*` keys live in the same flat table), and notification
 * *history keys* (§12.1's dedup guard against re-notifying past events).
 * Deliberately excluded: occurrence cache, OVATION grids, diagnostics.
 *
 * §12.3's forward-compatibility note: no field here may encode which app
 * or store produced the file beyond the purely informational [appVersion].
 */
@Serializable
data class SyncFile(
    val format: String = FORMAT,
    val formatVersion: Int = FORMAT_VERSION,
    val exportedAt: Instant,
    val appVersion: String,
    val locations: List<SavedLocation>,
    val rules: List<Rule>,
    val settings: Map<String, String>,
    val firedNotificationIds: List<String>,
) {
    companion object {
        const val FORMAT = "skyward-sync"
        const val FORMAT_VERSION = 1
    }
}
