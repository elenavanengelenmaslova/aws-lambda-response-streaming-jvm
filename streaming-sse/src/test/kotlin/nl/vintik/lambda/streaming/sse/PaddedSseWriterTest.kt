package nl.vintik.lambda.streaming.sse

import nl.vintik.lambda.streaming.sse.PaddedSseWriter.Companion.DEFAULT_HEAD_PAD_BYTES
import nl.vintik.lambda.streaming.sse.PaddedSseWriter.Companion.DEFAULT_HEAD_PAD_EVENTS
import nl.vintik.lambda.streaming.sse.PaddedSseWriter.Companion.DEFAULT_TAIL_PAD_BYTES
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.OutputStream

/**
 * Example/edge-case unit tests for [PaddedSseWriter] — the byte-level SSE + padding encoder promoted
 * into the `streaming-sse` module. Universal round-trip and padding properties live in the
 * sibling property-test files; this file pins concrete behaviours and the new module surface
 * (default constants, no-arg constructor, [PaddedSseWriter.hasPadding]).
 */
class PaddedSseWriterTest {

    private class CountingFlushStream : OutputStream() {
        val buf = ByteArrayOutputStream()
        var flushes = 0
        override fun write(b: Int) = buf.write(b)
        override fun write(b: ByteArray) = buf.write(b)
        override fun write(b: ByteArray, off: Int, len: Int) = buf.write(b, off, len)
        override fun flush() { flushes++ }
    }

    @Test
    fun `Given a token When writeEvent Then it emits a data line and flushes once`() {
        // Given
        val writer = PaddedSseWriter(headPadBytes = 0, tailPadBytes = 0, headPadEvents = 0)
        val out = CountingFlushStream()

        // When
        writer.writeEvent(out, "hello")

        // Then
        assertEquals("data: hello\n\n", out.buf.toByteArray().decodeToString())
        assertEquals(1, out.flushes, "each event must flush exactly once")
    }

    @Test
    fun `Given head and tail tiers When several events Then head events are padded larger than tail events`() {
        // Given — head pad 500 for the first 2 events, tail pad 50 afterwards.
        val writer = PaddedSseWriter(headPadBytes = 500, tailPadBytes = 50, headPadEvents = 2)
        val sizes = (1..4).map { writer.writeEvent(CountingFlushStream(), "t$it") }

        // Then — first two writes are much larger (head padding) than the last two (tail padding).
        assertTrue(sizes[0] > 500 && sizes[1] > 500, "head events should carry head padding, sizes=$sizes")
        assertTrue(sizes[2] in 50..200 && sizes[3] in 50..200, "tail events should carry tail padding, sizes=$sizes")
        assertTrue(sizes[0] > sizes[3], "head padded event must be larger than tail padded event")
    }

    @Test
    fun `Given no padding When writeEvent Then no SSE comment line is emitted`() {
        val writer = PaddedSseWriter(headPadBytes = 0, tailPadBytes = 0, headPadEvents = 3)
        val out = CountingFlushStream()
        writer.writeEvent(out, "x")
        assertFalse(out.buf.toByteArray().decodeToString().contains("\n: "), "no padding comment expected")
    }

    @Test
    fun `Given writeEnd When called Then it emits the exact terminal DONE bytes and flushes once`() {
        val writer = PaddedSseWriter(0, 0, 0)
        val out = CountingFlushStream()
        writer.writeEnd(out)
        assertEquals("event: end\ndata: [DONE]\n\n", out.buf.toByteArray().decodeToString())
        assertEquals(1, out.flushes, "writeEnd must flush exactly once")
    }

    @Test
    fun `Given negative padding When constructed Then it is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { PaddedSseWriter(-1, 0, 0) }
        assertThrows(IllegalArgumentException::class.java) { PaddedSseWriter(0, -1, 0) }
        assertThrows(IllegalArgumentException::class.java) { PaddedSseWriter(0, 0, -1) }
    }

    @Test
    fun `Given the no-arg constructor When an event is written Then it applies the three default tier constants`() {
        // The published defaults the no-arg constructor must apply.
        assertEquals(102_400, DEFAULT_HEAD_PAD_BYTES, "DEFAULT_HEAD_PAD_BYTES constant")
        assertEquals(10_240, DEFAULT_TAIL_PAD_BYTES, "DEFAULT_TAIL_PAD_BYTES constant")
        assertEquals(3, DEFAULT_HEAD_PAD_EVENTS, "DEFAULT_HEAD_PAD_EVENTS constant")

        // Given a no-arg writer, When writing more events than the head tier covers,
        // Then the first DEFAULT_HEAD_PAD_EVENTS events carry head padding and the next carries tail padding.
        val writer = PaddedSseWriter()
        val sizes = (1..DEFAULT_HEAD_PAD_EVENTS + 1).map { writer.writeEvent(CountingFlushStream(), "t$it") }

        for (i in 0 until DEFAULT_HEAD_PAD_EVENTS) {
            assertTrue(
                sizes[i] > DEFAULT_HEAD_PAD_BYTES,
                "default head event $i must exceed DEFAULT_HEAD_PAD_BYTES, sizes=$sizes",
            )
        }
        val tailSize = sizes[DEFAULT_HEAD_PAD_EVENTS]
        assertTrue(
            tailSize > DEFAULT_TAIL_PAD_BYTES && tailSize < DEFAULT_HEAD_PAD_BYTES,
            "the event after the head tier must carry tail (not head) padding, sizes=$sizes",
        )
    }

    @Test
    fun `Given padding on at least one tier When hasPadding Then it reports true`() {
        assertTrue(PaddedSseWriter(headPadBytes = 1, tailPadBytes = 0, headPadEvents = 1).hasPadding())
        assertTrue(PaddedSseWriter(headPadBytes = 0, tailPadBytes = 1, headPadEvents = 1).hasPadding())
        assertTrue(PaddedSseWriter().hasPadding(), "the default writer carries padding")
    }

    @Test
    fun `Given both tiers zero When hasPadding Then it reports false`() {
        assertFalse(PaddedSseWriter(headPadBytes = 0, tailPadBytes = 0, headPadEvents = 3).hasPadding())
    }
}
