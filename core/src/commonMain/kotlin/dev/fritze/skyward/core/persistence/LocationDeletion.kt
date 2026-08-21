package dev.fritze.skyward.core.persistence

import dev.fritze.skyward.core.rules.rulesAfterLocationDeletion
import kotlin.time.Instant

/**
 * Deleting a saved location, in full.
 *
 * It is not the single-row delete both frontends used to perform. Two other
 * things hang off a location and neither is enforced by the schema:
 *
 * - §9.1's rules address locations by id, so every reference to the deleted
 *   one has to be rewritten (see `rulesAfterLocationDeletion`) or the rule
 *   silently stops matching anything.
 * - §5's "primary" flag is what the sky chart and the map's home marker
 *   default to; deleting the primary without promoting a successor leaves
 *   those screens with nothing to pick. Desktop did this promotion inline and
 *   Android did not, which is exactly the kind of drift P2 exists to prevent.
 *
 * Callers still own replanning afterwards -- that is platform work (alarms on
 * Android, the desktop scheduler), not part of the delete.
 */
suspend fun deleteLocation(
    locationRepo: LocationRepo,
    ruleRepo: RuleRepo,
    locationId: String,
    now: Instant,
) {
    // getAll(), not observeVisible(): §13.3's hidden system rules (per-event
    // mutes, one-off reminders) carry location ids too, and a dangling
    // reference in one of those is invisible rather than harmless.
    val repaired = rulesAfterLocationDeletion(locationId, ruleRepo.getAll(), now)
    val wasPrimary = locationRepo.getById(locationId)?.isPrimary == true

    locationRepo.delete(locationId)
    for (rule in repaired) ruleRepo.upsert(rule)
    if (wasPrimary) {
        locationRepo.getAll().firstOrNull()?.let { locationRepo.upsert(it.copy(isPrimary = true, modifiedAt = now)) }
    }
}
