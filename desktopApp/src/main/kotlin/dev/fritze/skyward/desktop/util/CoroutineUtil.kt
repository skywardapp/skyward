package dev.fritze.skyward.desktop.util

import kotlinx.coroutines.CancellationException

/**
 * Like [runCatching], but never swallows [CancellationException]: coroutine
 * cancellation must always propagate for structured concurrency to work,
 * and plain `runCatching` (catching `Throwable`) traps it just like any
 * other failure. The desktop twin of the Android app's identical helper.
 */
inline fun <T> runCatchingCancellable(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }
