package nl.vintik.lambda.bedrock

import java.io.OutputStream

/**
 * Writes model text deltas to the Lambda [OutputStream] as Server-Sent Events, appending tiered
 * SSE-comment padding so each flushed chunk crosses the AWS Lambda response-streaming runtime's
 * internal buffer (~100 KB) and is delivered to the client immediately.
 *
 * ### Why this exists
 * The Lambda streaming runtime holds small writes in an internal buffer and only forwards them to
 * the client when the buffer fills (~100 KB) or the stream ends — there is no `flush()` that
 * overrides it (confirmed by the AWS Builder article referenced in the module README). A raw token
 * relay (a few bytes per token) therefore arrives as a single terminal burst. Inflating each event
 * past the buffer threshold forces immediate delivery.
 *
 * ### The trick
 * SSE comment lines start with `:` and are silently ignored by compliant clients (and by a plain
 * `sed -n 's/^data: //p'` filter). Each event is written as:
 * ```
 * data: <token text>\n\n
 * : <padding spaces>\n\n
 * ```
 * The padding is **tiered**: [headPadBytes] on the first [headPadEvents] events to break the
 * initial buffer, then [tailPadBytes] on every event afterwards to keep the stream flowing (the
 * runtime re-buffers after the first few events without ongoing tail padding).
 *
 * Trade-off: the padding inflates the response dramatically (a ~15 KB answer can become several MB).
 * That is the documented cost of progressive small-chunk delivery through API Gateway REST.
 *
 * This writer owns only the SSE + padding encoding; the metadata prelude is written by
 * `streaming-core`'s `ResponseWriter` before the first event.
 */
class PaddedSseWriter(
    private val headPadBytes: Int,
    private val tailPadBytes: Int,
    private val headPadEvents: Int,
) {
    init {
        require(headPadBytes >= 0) { "headPadBytes must be >= 0 but was $headPadBytes" }
        require(tailPadBytes >= 0) { "tailPadBytes must be >= 0 but was $tailPadBytes" }
        require(headPadEvents >= 0) { "headPadEvents must be >= 0 but was $headPadEvents" }
    }

    private var eventIndex = 0

    /**
     * Writes [text] as one SSE `data:` event followed by a tiered SSE-comment padding block, then
     * flushes [output]. Returns the number of bytes written (including padding), useful for tests
     * and for logging the inflation factor.
     */
    fun writeEvent(output: OutputStream, text: String): Int {
        val pad = if (eventIndex < headPadEvents) headPadBytes else tailPadBytes
        val sb = StringBuilder(text.length + pad + 16)
        sb.append("data: ").append(text).append("\n\n")
        if (pad > 0) sb.append(": ").append(" ".repeat(pad)).append("\n\n")
        val bytes = sb.toString().toByteArray(Charsets.UTF_8)
        output.write(bytes)
        output.flush()
        eventIndex++
        return bytes.size
    }

    /** Writes the terminal SSE event signalling end of stream, then flushes. */
    fun writeEnd(output: OutputStream) {
        output.write("event: end\ndata: [DONE]\n\n".toByteArray(Charsets.UTF_8))
        output.flush()
    }

    /** Number of events written so far (for logging/metrics). */
    fun eventsWritten(): Int = eventIndex
}
