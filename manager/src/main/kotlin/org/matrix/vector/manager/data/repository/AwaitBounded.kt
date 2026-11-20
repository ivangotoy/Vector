package org.matrix.vector.manager.data.repository

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Waits at most [timeoutMs] for [block], which may block its thread and ignore cancellation.
 *
 * `withTimeoutOrNull` around a blocking call cannot fire: cancellation does not interrupt a Binder
 * transaction, and the timeout waits for the cancelled child to finish before it returns. So the
 * call runs on [scope] instead, which nothing here joins, and only the wait for it is timed.
 *
 * Every non-null answer has exactly one owner. Either it is returned, or, when the wait ended
 * without it (timeout, or the caller was cancelled), it goes to [onLate] whenever it arrives, so a
 * descriptor that turns up after nobody is waiting any more is closed rather than left to the GC.
 */
suspend fun <T : Any> awaitBounded(
    timeoutMs: Long,
    scope: CoroutineScope,
    onLate: (T) -> Unit,
    block: suspend () -> T?,
): T? {
    val call = scope.async { block() }
    var answer: T? = null
    try {
        answer = withTimeoutOrNull(timeoutMs) { call.await() }
        return answer
    } finally {
        if (answer == null) {
            call.invokeOnCompletion { cause -> if (cause == null) call.getCompleted()?.let(onLate) }
        }
    }
}
