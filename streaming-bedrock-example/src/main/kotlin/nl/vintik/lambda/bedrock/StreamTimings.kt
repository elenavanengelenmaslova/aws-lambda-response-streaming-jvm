package nl.vintik.lambda.bedrock

import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Per-request timing log lines for locating where streamed bytes are held: when Bedrock delivers
 * deltas, when the handler writes them, and how long each write takes.
 *
 * Every line carries the Lambda request id (the same value API Gateway logs as
 * `$context.integration.requestId` in the stage access log), the time since the handler started and
 * the epoch time, so it can be lined up with the access log and with the client's timings. Event
 * lines are sampled — the first [SAMPLED_HEAD_EVENTS], then every [SAMPLE_EVERY]th — to keep one
 * request to about a dozen lines.
 */
internal class StreamTimings(private val requestId: String) {
    private val startNanos = System.nanoTime()
    private var firstDeltaSeen = false
    private var lastDeltaMs = -1L
    private var bytesTotal = 0L
    private var writeNanosTotal = 0L
    private var writeNanosMax = 0L

    fun mark(stage: String, extra: String = "") {
        val suffix = if (extra.isEmpty()) "" else " $extra"
        logger.info {
            "timing requestId=$requestId stage=$stage tMs=${sinceStartMs()} epochMs=${System.currentTimeMillis()}$suffix"
        }
    }

    /** Records that a model delta reached the handler; logs the first one. */
    fun onDelta() {
        lastDeltaMs = sinceStartMs()
        if (!firstDeltaSeen) {
            firstDeltaSeen = true
            mark("firstDelta")
        }
    }

    /** Runs [write] for event number [eventNo] (1-based), records its duration and bytes, and logs sampled events. */
    fun timeEventWrite(eventNo: Int, write: () -> Int): Int {
        val started = System.nanoTime()
        val bytes = write()
        val took = System.nanoTime() - started
        bytesTotal += bytes
        writeNanosTotal += took
        if (took > writeNanosMax) writeNanosMax = took
        if (eventNo <= SAMPLED_HEAD_EVENTS || eventNo % SAMPLE_EVERY == 0) {
            mark("event", "n=$eventNo writeMs=${ms(took)} bytesTotal=$bytesTotal")
        }
        return bytes
    }

    /** Runs [block] and logs [stage] with its duration. */
    fun <T> timed(stage: String, block: () -> T): T {
        val started = System.nanoTime()
        val result = block()
        mark(stage, "writeMs=${ms(System.nanoTime() - started)}")
        return result
    }

    fun streamEnd(events: Int) = mark(
        "streamEnd",
        "events=$events lastDeltaMs=$lastDeltaMs bytesTotal=$bytesTotal " +
            "writeMsTotal=${ms(writeNanosTotal)} writeMsMax=${ms(writeNanosMax)}",
    )

    private fun sinceStartMs(): Long = (System.nanoTime() - startNanos) / NANOS_PER_MILLI

    private fun ms(nanos: Long): String = "%.1f".format(nanos / NANOS_PER_MILLI.toDouble())

    companion object {
        const val SAMPLED_HEAD_EVENTS = 3
        const val SAMPLE_EVERY = 100
        private const val NANOS_PER_MILLI = 1_000_000L
    }
}
