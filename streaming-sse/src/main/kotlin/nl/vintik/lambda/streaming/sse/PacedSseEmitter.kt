package nl.vintik.lambda.streaming.sse

import java.io.OutputStream

/**
 * Adds an optional, bounded inter-chunk *pacing* (dribble) delay on top of a [PaddedSseWriter].
 *
 * ### Why this is separate from the writer
 * [PaddedSseWriter] is a pure, deterministic SSE + padding encoder — it never sleeps, so it stays
 * trivially unit- and property-testable. Pacing is a distinct responsibility (*when* to write, not
 * *how* to encode), so it lives here. The sleeper is injected ([sleep], defaulting to
 * [Thread.sleep]) so pacing tests can assert the sleep schedule with zero wall-clock delay.
 *
 * ### Why delay requires padding
 * An inter-chunk delay on its own does nothing: without padding each small write never crosses the
 * Lambda streaming runtime's internal buffer, so the delayed chunks still coalesce into a single
 * terminal burst. The delay only paces delivery *once padding is present* — hence a non-[NO_DELAY]
 * delay combined with a writer that carries no padding is rejected at construction.
 *
 * ### Pacing boundary semantics
 * The delay applies *in the gap between completing one event write and starting the next*. With no
 * look-ahead inside a single [emit] call, this is implemented as "sleep before every event except
 * the first" (sleep only when a delay is configured and the event index is greater than 0), which
 * produces exactly the same inter-event gaps and guarantees no pause before the first event and
 * none after the last. Each event is written and flushed by the writer *before* any pause, so every
 * paced chunk crosses the buffer before the delay. [end] never applies a trailing delay.
 */
public class PacedSseEmitter(
    private val writer: PaddedSseWriter,
    private val delayMillis: Int = NO_DELAY,
    private val sleep: (Long) -> Unit = Thread::sleep,
) {
    init {
        require(delayMillis <= MAX_DELAY_MILLIS) {
            "delayMillis must be <= $MAX_DELAY_MILLIS but was $delayMillis"
        }
        require(delayMillis == NO_DELAY || delayMillis >= MIN_DELAY_MILLIS) {
            "delayMillis must be $NO_DELAY or >= $MIN_DELAY_MILLIS but was $delayMillis"
        }
        require(delayMillis == NO_DELAY || writer.hasPadding()) {
            "a configured delay ($delayMillis ms) requires the writer to carry padding: " +
                "delay alone cannot cross the Lambda streaming runtime buffer"
        }
    }

    private var eventIndex = 0

    /**
     * Writes [text] as one padded SSE event via the writer (which flushes before returning), then —
     * if a delay is configured and this is not the first event — pauses for [delayMillis]. The
     * event is always written and flushed before any pause. Returns the byte count reported by the
     * underlying [PaddedSseWriter.writeEvent].
     */
    public fun emit(output: OutputStream, text: String): Int {
        if (delayMillis != NO_DELAY && eventIndex > 0) {
            sleep(delayMillis.toLong())
        }
        val bytes = writer.writeEvent(output, text)
        eventIndex++
        return bytes
    }

    /** Delegates to [PaddedSseWriter.writeEnd]; never applies a trailing delay. */
    public fun end(output: OutputStream) {
        writer.writeEnd(output)
    }

    /** Number of events written so far, delegating to [PaddedSseWriter.eventsWritten]. */
    public fun eventsWritten(): Int = writer.eventsWritten()

    public companion object {
        /** Sentinel meaning "no pacing": events are written with zero added pause. */
        public const val NO_DELAY: Int = 0

        /** Smallest configurable inter-chunk delay in milliseconds. */
        public const val MIN_DELAY_MILLIS: Int = 1

        /** Largest configurable inter-chunk delay in milliseconds. */
        public const val MAX_DELAY_MILLIS: Int = 5000
    }
}
