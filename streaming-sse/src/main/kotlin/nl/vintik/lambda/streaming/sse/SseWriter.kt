package nl.vintik.lambda.streaming.sse

import java.io.OutputStream

/**
 * Writes Server-Sent Events to a streamed Lambda response.
 *
 * Framing only. Each event is written and flushed immediately, which on a live stream (see
 * [StreamingResponse]) is enough for the client to receive it straight away: a 24-byte event
 * measured at 19–46 ms to API Gateway on a six-second stream.
 *
 * ### Multi-line text
 * A `data:` field carries one line. Text containing `\n` is emitted as one `data:` line per line,
 * as the SSE specification requires, so a client reassembles it intact.
 */
public class SseWriter {

    private var events = 0

    /**
     * Writes [text] as one SSE event, splitting it across `data:` lines on `\n`, then flushes.
     *
     * Empty text still produces a well-formed event (`data:` with no value), because a model may
     * legitimately emit an empty delta and silently dropping it would desynchronise event counts.
     *
     * @return the number of bytes written, useful for logging the real on-the-wire cost.
     */
    public fun writeEvent(output: OutputStream, text: String): Int {
        val frame = buildString(text.length + FRAME_OVERHEAD) {
            text.split('\n').forEach { line ->
                append(DATA_FIELD).append(line).append('\n')
            }
            append('\n')
        }
        return write(output, frame).also { events++ }
    }

    /**
     * Writes a named event (`event: <name>` followed by `data:` lines), then flushes. Use it for
     * out-of-band signals a client dispatches separately, such as progress or errors.
     *
     * @throws IllegalArgumentException if [name] contains a newline, which would corrupt the frame.
     */
    public fun writeNamedEvent(output: OutputStream, name: String, text: String): Int {
        require(!name.contains('\n')) { "An SSE event name must not contain a newline, but was '$name'" }
        val frame = buildString(name.length + text.length + FRAME_OVERHEAD) {
            append(EVENT_FIELD).append(name).append('\n')
            text.split('\n').forEach { line ->
                append(DATA_FIELD).append(line).append('\n')
            }
            append('\n')
        }
        return write(output, frame).also { events++ }
    }

    /**
     * Writes the terminal `event: end` / `data: [DONE]` frame and flushes, so a client can tell a
     * finished stream from a connection that dropped mid-answer.
     */
    public fun writeEnd(output: OutputStream): Int =
        writeNamedEvent(output, END_EVENT_NAME, DONE_SENTINEL)

    /** Number of events written so far, including the terminal one. */
    public fun eventsWritten(): Int = events

    private fun write(output: OutputStream, frame: String): Int {
        val bytes = frame.toByteArray(Charsets.UTF_8)
        output.write(bytes)
        // Deliberate: on a live stream this is what releases the event to the client.
        output.flush()
        return bytes.size
    }

    public companion object {
        /** Name of the terminal event written by [writeEnd]. */
        public const val END_EVENT_NAME: String = "end"

        /** Payload of the terminal event written by [writeEnd]. */
        public const val DONE_SENTINEL: String = "[DONE]"

        private const val DATA_FIELD = "data: "
        private const val EVENT_FIELD = "event: "
        private const val FRAME_OVERHEAD = 16
    }
}
