package nl.vintik.lambda.streaming.sse

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.io.ByteArrayOutputStream
import java.io.OutputStream

/**
 * Property-based test for [PaddedSseWriter].
 *
 * Feature: streaming-sse-module, Property 5: Returned byte count equals bytes written
 *
 * Validates: Requirements 2.9
 *
 * [PaddedSseWriter.writeEvent] returns the number of bytes it wrote for that event (the UTF-8
 * encoding of the `data:` line plus the optional padding comment). This property pins that contract:
 * for any payload and any padding tier, the returned count must equal the exact number of UTF-8
 * bytes actually written to the [OutputStream] for that event — including multi-byte characters,
 * which inflate the byte count beyond the string length.
 *
 * The corpus varies payloads (ASCII, multi-byte UTF-8, empty, embedded newlines, `data:`-like text)
 * and tiers (head/tail/zero padding) and emits multiple events per configuration so both the head
 * tier and the tail tier of a single writer are exercised within one run.
 */
class PaddedSseWriterByteCountTest {

    /** Minimal capturing stream — records the exact number of bytes written for one event. */
    private class CountingStream : OutputStream() {
        private val buf = ByteArrayOutputStream()
        override fun write(b: Int) = buf.write(b)
        override fun write(b: ByteArray) = buf.write(b)
        override fun write(b: ByteArray, off: Int, len: Int) = buf.write(b, off, len)
        override fun flush() { /* no-op: byte capture only */ }
        fun byteCount(): Int = buf.size()
    }

    @ParameterizedTest(name = "[{index}] head={1}B tail={2}B headEvents={3} payloads={0}")
    @MethodSource("configurations")
    fun `Feature streaming-sse-module Property 5 returned byte count equals bytes written`(
        payloads: List<String>,
        headPadBytes: Int,
        tailPadBytes: Int,
        headPadEvents: Int,
    ) {
        val writer = PaddedSseWriter(headPadBytes, tailPadBytes, headPadEvents)

        payloads.forEachIndexed { index, text ->
            // A fresh stream per event so the captured count is exactly this event's bytes.
            val stream = CountingStream()
            val returned = writer.writeEvent(stream, text)

            assertEquals(
                stream.byteCount(),
                returned,
                "writeEvent return value must equal the exact UTF-8 bytes written for event " +
                    "index $index (text=\"$text\", headPadBytes=$headPadBytes, " +
                    "tailPadBytes=$tailPadBytes, headPadEvents=$headPadEvents)",
            )
        }
    }

    private companion object {
        /**
         * 16 diverse configurations: (payloads, headPadBytes, tailPadBytes, headPadEvents).
         * Each configuration emits several events so a single writer exercises both the head tier
         * (index < headPadEvents) and the tail tier (index >= headPadEvents). Payloads mix ASCII,
         * multi-byte UTF-8 (where byte count != length), empty strings, embedded newlines and
         * `data:`-like text; tiers span zero, small and buffer-crossing padding sizes.
         */
        @JvmStatic
        fun configurations(): List<Arguments> = listOf(
            // Module defaults, plain ASCII across head and tail tiers.
            Arguments.of(listOf("hello", "world", "foo", "bar", "baz"), 102_400, 10_240, 3),
            // Zero padding on both tiers — return is just the `data:` line bytes.
            Arguments.of(listOf("a", "bb", "ccc"), 0, 0, 2),
            // Head padding only, zero tail — tail events carry no comment.
            Arguments.of(listOf("x", "y", "z", "w"), 50_000, 0, 2),
            // Tail padding only, zero head — head events carry no comment.
            Arguments.of(listOf("one", "two", "three"), 0, 4_096, 1),
            // Multi-byte UTF-8: emoji (4 bytes each) — byte count exceeds string length.
            Arguments.of(listOf("\uD83D\uDE80\uD83D\uDE80", "\uD83C\uDF0D"), 1_024, 512, 1),
            // Accented Latin (2 bytes each in UTF-8).
            Arguments.of(listOf("caf\u00E9", "na\u00EFve", "r\u00E9sum\u00E9"), 2_048, 256, 2),
            // CJK characters (3 bytes each in UTF-8).
            Arguments.of(listOf("\u4F60\u597D", "\u4E16\u754C", "\u6D41"), 128, 64, 1),
            // Empty payloads mixed with content.
            Arguments.of(listOf("", "x", "", "y"), 100, 50, 2),
            // Payload containing embedded newlines.
            Arguments.of(listOf("line1\nline2", "a\nb\nc"), 300, 100, 1),
            // Payload that itself looks like SSE `data:` / comment lines.
            Arguments.of(listOf("data: fake", ": comment", "data:x"), 200, 80, 2),
            // Buffer-crossing head padding, small tail, many events.
            Arguments.of(listOf("t0", "t1", "t2", "t3", "t4", "t5"), 120_000, 8_000, 3),
            // Single character payloads, head-only tier (all events head).
            Arguments.of(listOf("p", "q"), 4_000, 1_000, 5),
            // Mixed ASCII + multi-byte within the same run.
            Arguments.of(listOf("hi \uD83D\uDC4B", "end", "\u00E9\u00E8\u00EA"), 16_384, 2_048, 2),
            // Larger payload strings with tail padding.
            Arguments.of(listOf("x".repeat(500), "y".repeat(1_000), "z".repeat(50)), 2_000, 500, 1),
            // Whitespace and tab payloads.
            Arguments.of(listOf("  ", "\t\t", " a "), 64, 32, 1),
            // All multi-byte, zero padding — isolate UTF-8 byte counting from padding.
            Arguments.of(listOf("\u00E9", "\uD83D\uDE00", "\u4E16"), 0, 0, 1),
        )
    }
}
