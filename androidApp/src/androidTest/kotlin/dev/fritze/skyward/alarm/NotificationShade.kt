package dev.fritze.skyward.alarm

import android.content.Context
import android.os.SystemClock
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat

// §17.5's shared way of reading the notification shade back.
//
// Both notify() and cancelAll() are one-way calls into system_server: they
// return before the shade has caught up. Reading activeNotifications once,
// immediately after posting, is a race that usually wins -- the worst kind,
// because it makes a suite fail intermittently on CI rather than never. Every
// assertion about the shade therefore polls.
//
// These live here rather than privately in AlarmFlowInstrumentedTest because
// four suites now assert on posted notifications, and four copies of a polling
// loop drift apart.

/** Budget for a notification posted directly, which only has a binder hop to make. */
internal const val NOTIFICATION_TIMEOUT_MILLIS = 5_000L

/**
 * A reminder that has to travel through `AlarmManager` or `WorkManager` waits
 * on the OS's scheduling pass, not just on a binder hop, so it needs a budget
 * an order of magnitude larger than a direct `notify()`.
 */
internal const val SCHEDULED_DELIVERY_TIMEOUT_MILLIS = 30_000L

internal const val NOTIFICATION_POLL_MILLIS = 50L

/**
 * Proving a *negative* can only ever be "still nothing after a while"; the
 * full timeout would just be dead time on every such assertion.
 */
internal const val ABSENCE_GRACE_MILLIS = 500L

/** Polls `activeNotifications` until [predicate] holds, or the deadline passes. */
internal fun Context.awaitNotifications(
    timeoutMillis: Long = NOTIFICATION_TIMEOUT_MILLIS,
    predicate: (List<StatusBarNotification>) -> Boolean,
): List<StatusBarNotification> {
    val deadline = SystemClock.uptimeMillis() + timeoutMillis
    var active = NotificationManagerCompat.from(this).activeNotifications
    while (!predicate(active) && SystemClock.uptimeMillis() < deadline) {
        Thread.sleep(NOTIFICATION_POLL_MILLIS)
        active = NotificationManagerCompat.from(this).activeNotifications
    }
    return active
}

/** The posted notification for [notificationId], or null if it never arrived. */
internal fun Context.awaitPosted(
    notificationId: String,
    timeoutMillis: Long = NOTIFICATION_TIMEOUT_MILLIS,
): StatusBarNotification? =
    awaitNotifications(timeoutMillis) { list -> list.any { it.id == notificationId.hashCode() } }
        .firstOrNull { it.id == notificationId.hashCode() }

/** True if [notificationId] is still absent from the shade after a short grace period. */
internal fun Context.awaitNoPost(notificationId: String): Boolean =
    awaitNotifications(timeoutMillis = ABSENCE_GRACE_MILLIS) { list -> list.any { it.id == notificationId.hashCode() } }
        .none { it.id == notificationId.hashCode() }

/**
 * Clears the shade and waits for the clear to land, so one suite's teardown
 * cannot arrive after the next suite has already posted and take its
 * notification with it. Returns whatever is still up, for the caller to
 * assert on -- starting a test with a leftover notification should fail here,
 * not later as a confusing failure in the test body.
 */
internal fun Context.clearShade(): List<StatusBarNotification> {
    NotificationManagerCompat.from(this).cancelAll()
    return awaitNotifications { it.isEmpty() }
}

/**
 * Polls [read] until it returns a non-null value satisfying [predicate].
 * The database equivalent of [awaitNotifications], for the receivers and
 * workers whose only observable effect is a status or precision write:
 * `goAsync()` and `CoroutineWorker` both complete after the call that
 * triggered them returns, so nothing about them can be asserted synchronously.
 *
 * Sleeps on the calling thread rather than `delay()`-ing, even though every
 * caller is inside `runTest`: `runTest` fast-forwards virtual time, so a
 * `delay()` here would spin the loop out in microseconds and turn every poll
 * into a single read. Real elapsed time is the whole point.
 */
internal suspend fun <T> awaitValue(
    timeoutMillis: Long = NOTIFICATION_TIMEOUT_MILLIS,
    read: suspend () -> T,
    predicate: (T) -> Boolean,
): T {
    val deadline = SystemClock.uptimeMillis() + timeoutMillis
    var value = read()
    while (!predicate(value) && SystemClock.uptimeMillis() < deadline) {
        Thread.sleep(NOTIFICATION_POLL_MILLIS)
        value = read()
    }
    return value
}
