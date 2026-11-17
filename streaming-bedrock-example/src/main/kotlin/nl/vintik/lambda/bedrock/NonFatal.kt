package nl.vintik.lambda.bedrock

import kotlin.coroutines.cancellation.CancellationException

/**
 * Like [runCatching], but only captures recoverable exceptions.
 *
 * Plain `runCatching` catches every [Throwable], which would turn coroutine cancellation, thread
 * interruption, or a JVM [Error] (for example `OutOfMemoryError`) into an ordinary failure value.
 * Those must propagate instead: cancellation and interruption are control signals, and an [Error]
 * means the process is in no state to write an HTTP error response.
 */
internal inline fun <R> runCatchingNonFatal(block: () -> R): Result<R> =
    runCatching(block).onFailure { it.rethrowIfFatal() }

/** Rethrows [this] when it must not be handled as an ordinary failure. */
internal fun Throwable.rethrowIfFatal() {
    when (this) {
        is CancellationException, is InterruptedException, is Error -> throw this
        else -> Unit
    }
}
