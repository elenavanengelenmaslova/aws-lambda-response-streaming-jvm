package nl.vintik.lambda.bedrock

import kotlin.coroutines.cancellation.CancellationException

/**
 * Rethrows a fatal failure instead of keeping it in the [Result]; any other failure stays a failure.
 *
 * `runCatching` captures every [Throwable], including coroutine cancellation, thread interruption and
 * JVM [Error]s such as `OutOfMemoryError`. Those must propagate rather than be handled like an ordinary
 * failure, so every `runCatching` in this module is followed by `rethrowFatal()`:
 *
 * ```
 * runCatching { work() }.rethrowFatal().onFailure { e -> logger.warn(e) { "..." } }
 * ```
 */
internal fun <T> Result<T>.rethrowFatal(): Result<T> = onFailure { if (it.isFatal()) throw it }

internal fun Throwable.isFatal(): Boolean =
    this is CancellationException || this is InterruptedException || this is Error
