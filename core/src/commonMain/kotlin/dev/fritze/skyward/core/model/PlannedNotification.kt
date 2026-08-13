package dev.fritze.skyward.core.model

import kotlinx.serialization.Serializable
import kotlin.time.Instant

/** How precisely a reminder can actually be delivered (§10.1) — a property of the OS, not the data. */
enum class Precision { EXACT, APPROXIMATE }

enum class NotificationStatus { PENDING, REGISTERED, FIRED, CANCELLED, MISSED }

/**
 * One reminder the planner intends to deliver. Mirrors the
 * `planned_notification` table (§10.4); [id] is the dedup key and is
 * derived, never random: `"<occurrenceId>|<anchorEpochSec>|<leadSec|first>"`.
 */
@Serializable
data class PlannedNotification(
    val id: String,
    val occurrenceId: String,
    val ruleId: String, // first rule that produced it
    val locationId: String, // best-quality matching location
    val fireAt: Instant,
    val status: NotificationStatus,
    val precision: Precision, // EXACT until AlarmScheduler reports otherwise
    val title: String,
    val body: String, // rendered at PLAN time; see §10.4 for the one thing deferred to fire time
    val createdAt: Instant,
    val firedAt: Instant?,
)
