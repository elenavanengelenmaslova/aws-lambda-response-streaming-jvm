package nl.vintik.lambda.streaming.sse

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.io.ByteArrayOutputStream
import java.io.OutputStream

/**
 * Property-based test for [PaddedSseWriter].
 *
 * Feature: streaming-sse-module, Property 4: Tiered padding crosses the buffer threshold
 *
 * Validates: Requirements 2.3, 2.4, 2.6
 *
 * The AWS Lambda response-streaming runtime holds small writes in an internal buffer (~100 KB) and
 * only forwards them when the buffer fills or the stream ends. To force progressive delivery the
 * writer inflates each event with tiered SSE-comment padding:
 *   - the first [PaddedSseWriter.writeEvent] events (zero-based index < headPadEvents) carry
 *     `headPadBytes` of padding, so a head event must cross the ~100 KB buffer threshold;
 *   - every subsequent event carries at least `tailPadBytes` of padding to keep the stream flowing.
 *
 * This test asserts both tiers hold across a diverse corpus of configurations by measuring the
 * exact per-event byte size (both the return value of `writeEvent` and the bytes captured on the
 * stream — they must agree).
 */
class PaddedSseWriterBufferThresholdTest {

    /** Minimal capturing stream — records every byte written and counts flushes. */
    private class CapturingStream : OutputStream() {
        val buf = ByteArrayOutputStream()
        var flushes = 0
        override fun write(b: Int) = buf.write(b)
        override fun write(b: ByteArray) = buf.write(b)
        override fun write(b: ByteArray, off: Int, len: Int) = buf.write(b, off, len)
        override fun flush() { flushes++ }
        fun size(): Int = buf.size()
    }

    /** The ~100 KB AWS runtime buffer threshold a head-tier event must exceed. */
    private val bufferThreshold = 100_000

    @ParameterizedTest(name = "[{index}] head={0}B x{2} events, tail={1}B, total={3}")
    @MethodSource("configurations")
    fun `Feature streaming-sse-module Property 4 tiered padding crosses the buffer threshold`(
        headPadBytes: Int,
        tailPadBytes: Int,
        headPadEvents: Int,
        eventCount: Int,
    ) {
        val writer = PaddedSseWriter(headPadBytes, tailPadBytes, headPadEvents)

        repeat(eventCount) { index ->
            val stream = CapturingStream()
            val text = "token-$index"
            val returned = writer.writeEvent(stream, text)

            // The return value must equal the exact bytes captured on the stream.
            assertTrue(
                returned == stream.size(),
                "returned byte count ($returned) must equal bytes written (${stream.size()}) " +
                    "at event index $index",
            )
            assertTrue(stream.flushes == 1, "event $index must flush exactly once")

            val isHeadTier = index < headPadEvents
            if (isHeadTier && headPadBytes >= bufferThreshold) {
                // Requirements 2.3/2.6: a head-tier event must cross the ~100 KB buffer threshold.
                assertTrue(
                    returned > bufferThreshold,
                    "head-tier event $index with headPadBytes=$headPadBytes must exceed " +
                        "~100 KB buffer threshold but was $returned bytes",
                )
            }

            if (!isHeadTier && tailPadBytes > 0) {
                // Requirement 2.4: a tail-tier event must carry at least tailPadBytes of padding.
                // The event is `data: <text>\n\n` plus a `: <padding>\n\n` comment, so the total
                // size necessarily exceeds the raw padding byte count.
                assertTrue(
                    returned >= tailPadBytes,
                    "tail-tier event $index must carry at least tailPadBytes=$tailPadBytes of " +
                        "padding but total was only $returned bytes",
                )
                // And it must stay below the head tier when head padding is larger (tiers differ).
                if (headPadBytes > tailPadBytes) {
                    assertTrue(
                        returned < headPadBytes,
                        "tail-tier event $index ($returned bytes) must be smaller than a head " +
                            "event (headPadBytes=$headPadBytes)",
                    )
                }
            }
        }
    }

    private companion object {
        /**
         * 16 diverse configurations: (headPadBytes, tailPadBytes, headPadEvents, eventCount).
         * Head sizes span above and below the ~100 KB threshold; tail sizes span 0..large; head
         * event counts and total event counts vary so both tiers are exercised in every run.
         */
        @JvmStatic
        fun configurations(): List<Arguments> = listOf(
            // Module defaults — head crosses the threshold, tail keeps it flowing.
            Arguments.of(102_400, 10_240, 3, 6),
            Arguments.of(100_000, 1, 1, 2),
            Arguments.of(120_000, 10_000, 2, 5),
            Arguments.of(150_000, 50_000, 1, 4),
            Arguments.of(200_000, 100_000, 3, 7),
            Arguments.of(250_000, 5_000, 4, 8),
            Arguments.of(100_001, 20_480, 2, 3),
            Arguments.of(110_000, 10_240, 5, 10),
            Arguments.of(300_000, 1_024, 1, 6),
            Arguments.of(100_000, 100_000, 3, 3),
            // Head tier covers every event — no tail events written.
            Arguments.of(128_000, 8_000, 10, 4),
            // tailPadBytes == 0 — tail tier carries no padding (no padding assertion for tail).
            Arguments.of(104_000, 0, 2, 5),
            // Large tail padding below the threshold.
            Arguments.of(160_000, 90_000, 2, 6),
            // Single head event, many tail events.
            Arguments.of(100_500, 12_000, 1, 12),
            // headPadBytes == threshold exactly plus one, tiny tail.
            Arguments.of(100_001, 1, 3, 9),
            // Big head, moderate tail, moderate counts.
            Arguments.of(180_000, 30_000, 2, 8),
        )
    }
}
