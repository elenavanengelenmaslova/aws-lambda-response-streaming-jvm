package nl.vintik.lambda.streaming.sse

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.io.ByteArrayOutputStream

/**
 * Property-based test for [PacedSseEmitter].
 *
 * Feature: streaming-sse-module, Property 6: Pacing sleeps once per inter-event gap and never at the boundaries
 *
 * Validates: Requirements 3.1, 3.5
 *
 * The emitter applies the configured delay only in the gap *between* consecutive events — never
 * before the first event and never after the last (nor on [PacedSseEmitter.end]). Implemented as
 * "sleep before every event except the first", this means emitting `n` events produces exactly
 * `max(0, n - 1)` inter-event gaps, each paced with the configured delay.
 *
 * With an injected recording sleeper (zero wall-clock delay), for any delay in `[1, 5000]` and any
 * event count `n`, emitting `n` events then calling `end` must invoke the sleeper exactly `n - 1`
 * times, each with `delay.toLong()`, with no invocation before the first event and none on `end`.
 *
 * A configured (non-zero) delay requires the writer to carry padding, so the writer is constructed
 * with padding (`PaddedSseWriter(100, 10, 1)`).
 */
class PacedSseEmitterPacingPropertyTest {

    /** Records each sleep argument so the sleep schedule can be asserted with zero wall-clock delay. */
    private fun recordingSleeper(recorded: MutableList<Long>): (Long) -> Unit = { recorded.add(it) }

    @ParameterizedTest(name = "[{index}] delay={0}ms events={1}")
    @MethodSource("delayAndEventCount")
    fun `Feature streaming-sse-module Property 6 pacing sleeps once per inter-event gap and never at boundaries`(
        delay: Int,
        eventCount: Int,
    ) {
        val recorded = mutableListOf<Long>()
        // A configured delay requires the writer to carry padding.
        val writer = PaddedSseWriter(100, 10, 1)
        val emitter = PacedSseEmitter(writer, delayMillis = delay, sleep = recordingSleeper(recorded))
        val output = ByteArrayOutputStream()

        // No sleep may happen before the first event is emitted.
        assertTrue(
            recorded.isEmpty(),
            "sleeper must not be invoked before any event is emitted (delay=$delay, events=$eventCount)",
        )

        repeat(eventCount) { index ->
            val sleepsBefore = recorded.size
            emitter.emit(output, "event-$index")
            if (index == 0) {
                // The first event must not be preceded by a pause.
                assertEquals(
                    0,
                    recorded.size,
                    "no sleep may occur before the first event (delay=$delay, events=$eventCount)",
                )
            } else {
                // Every subsequent event is preceded by exactly one paced gap.
                assertEquals(
                    sleepsBefore + 1,
                    recorded.size,
                    "each event after the first must add exactly one sleep (index=$index, delay=$delay)",
                )
            }
        }

        val sleepsBeforeEnd = recorded.size
        emitter.end(output)

        // end() never applies a trailing delay.
        assertEquals(
            sleepsBeforeEnd,
            recorded.size,
            "end() must not invoke the sleeper (delay=$delay, events=$eventCount)",
        )

        // Exactly n-1 inter-event gaps, each with the configured delay.
        val expectedSleeps = maxOf(0, eventCount - 1)
        assertEquals(
            expectedSleeps,
            recorded.size,
            "sleeper must be invoked exactly max(0, n-1) times (n=$eventCount, delay=$delay)",
        )
        recorded.forEachIndexed { gapIndex, actual ->
            assertEquals(
                delay.toLong(),
                actual,
                "every recorded sleep must equal the configured delay (gap=$gapIndex, delay=$delay)",
            )
        }
    }

    private companion object {
        /**
         * 18 diverse (delay, eventCount) combinations. Delays span the minimum (1), several
         * mid-range values, and the maximum (5000). Event counts include the boundary cases 0, 1,
         * and 2 (where n-1 flips from 0 to 1) plus larger sequences, each crossed with different
         * delays so both the delay value and the gap count vary across the corpus.
         */
        @JvmStatic
        fun delayAndEventCount(): List<Arguments> = listOf(
            // Boundary event counts at the minimum delay.
            Arguments.of(1, 0),
            Arguments.of(1, 1),
            Arguments.of(1, 2),
            Arguments.of(1, 10),
            // Minimum-plus delay with varied counts.
            Arguments.of(2, 5),
            Arguments.of(5, 3),
            // Mid-range delays.
            Arguments.of(50, 0),
            Arguments.of(100, 1),
            Arguments.of(250, 4),
            Arguments.of(500, 7),
            Arguments.of(1000, 2),
            Arguments.of(1500, 15),
            Arguments.of(2500, 1),
            Arguments.of(3000, 6),
            // Maximum delay across boundary and larger counts.
            Arguments.of(5000, 0),
            Arguments.of(5000, 1),
            Arguments.of(5000, 2),
            Arguments.of(5000, 20),
        )
    }
}
