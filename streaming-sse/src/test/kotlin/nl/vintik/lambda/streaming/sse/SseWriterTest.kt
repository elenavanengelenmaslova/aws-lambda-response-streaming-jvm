package nl.vintik.lambda.streaming.sse

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.ByteArrayOutputStream

/**
 * Framing tests for [SseWriter]. The headline property is the round trip: whatever a conforming SSE
 * client reassembles from the `data:` fields must equal the text that was written, including text
 * containing newlines.
 */
class SseWriterTest {

    private val sink = ByteArrayOutputStream()
    private val writer = SseWriter()

    private fun written() = sink.toString(Charsets.UTF_8)

    /**
     * The `data:` payload a conforming client reassembles: strip the prefix, re-join with `\n`.
     *
     * Splits on `'\n'` only, deliberately — `String.lines()` would also split on a bare `\r`, which
     * SSE does not treat as a field separator inside a `data:` value and the writer does not either.
     */
    private fun clientView(frame: String): String =
        frame.split('\n').filter { it.startsWith("data: ") }.joinToString("\n") { it.removePrefix("data: ") }

    @Test
    fun `Given plain text When an event is written Then the frame is a single data line ended by a blank line`() {
        writer.writeEvent(sink, "hello")

        assertEquals("data: hello\n\n", written())
    }

    @Test
    fun `Given text containing newlines When an event is written Then every line carries a data prefix and the text round-trips`() {
        writer.writeEvent(sink, "first\nsecond\nthird")

        assertEquals("data: first\ndata: second\ndata: third\n\n", written())
        assertEquals("first\nsecond\nthird", clientView(written()))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "a", "token", "with spaces", "trailing ", " leading", "multi\nline", "\nleading newline",
            "trailing newline\n", "a\n\nb", "unicode — em dash", "emoji 🙂", "json {\"k\":\"v\"}",
            "colon: inside", "data: looks like a field", "100 chars ----------------------------",
            "tab\tseparated", "\r carriage return", "a\n\n\nb",
        ],
    )
    fun `Given diverse deltas When written as events Then a conforming client reassembles the original text`(text: String) {
        writer.writeEvent(sink, text)

        // \r is not a line separator for String.split('\n'), so it survives inside its own data line.
        assertEquals(text, clientView(written()))
    }

    @Test
    fun `Given empty text When an event is written Then a well-formed empty event is emitted rather than nothing`() {
        val bytes = writer.writeEvent(sink, "")

        assertEquals("data: \n\n", written())
        assertEquals(8, bytes)
        assertEquals(1, writer.eventsWritten())
    }

    @Test
    fun `Given an event When written Then the returned byte count matches the bytes on the stream`() {
        val bytes = writer.writeEvent(sink, "héllo")

        assertEquals(sink.size(), bytes)
    }

    @Test
    fun `Given a named event When written Then the event field precedes the data lines`() {
        writer.writeNamedEvent(sink, "progress", "50%")

        assertEquals("event: progress\ndata: 50%\n\n", written())
    }

    @Test
    fun `Given a name containing a newline When a named event is written Then it is rejected rather than corrupting the frame`() {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            writer.writeNamedEvent(sink, "bad\nname", "x")
        }

        assertEquals(true, failure.message?.contains("newline"))
    }

    @Test
    fun `Given a finished stream When end is written Then the terminal event carries the DONE sentinel`() {
        writer.writeEnd(sink)

        assertEquals("event: ${SseWriter.END_EVENT_NAME}\ndata: ${SseWriter.DONE_SENTINEL}\n\n", written())
    }

    @Test
    fun `Given several events and an end When all are written Then the event count includes the terminal event`() {
        writer.writeEvent(sink, "one")
        writer.writeEvent(sink, "two")
        writer.writeEnd(sink)

        assertEquals(3, writer.eventsWritten())
    }

    @Test
    fun `Given a stream When an event is written Then it is flushed so a live connection releases it`() {
        var flushes = 0
        val counting = object : ByteArrayOutputStream() {
            override fun flush() {
                flushes++
                super.flush()
            }
        }

        writer.writeEvent(counting, "x")

        assertEquals(1, flushes)
    }
}
