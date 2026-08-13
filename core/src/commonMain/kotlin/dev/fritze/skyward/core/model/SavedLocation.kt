package dev.fritze.skyward.core.model

import kotlinx.serialization.Serializable
import kotlin.time.Instant

/**
 * A user-named location. Exactly one [isPrimary] location is expected to
 * exist at a time; enforcing that invariant is a repository concern, not
 * this type's.
 */
@Serializable
data class SavedLocation(
    val id: String, // UUID v4
    val name: String, // "Home", "Cabin"
    val point: GeoPoint,
    val isPrimary: Boolean,
    val createdAt: Instant,
    val modifiedAt: Instant, // drives sync merge (§12.3)
)
