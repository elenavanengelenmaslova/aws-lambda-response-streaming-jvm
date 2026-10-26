package nl.vintik.lambda.streaming.sse

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.io.ByteArrayOutputStream
import java.util.stream.Stream

/**
 * Property-based test for [PacedSseEmitter] (Feature: streaming-sse-module, Property 7).
 *
 * Property 7 — no pacing means no added pause: for any event sequence emitted with
 * [PacedSseEmitter.NO_DELAY], the injected sleeper is never invoked, including after [end].
 * This pins the boundary meaning of the no-delay sentinel (Requirement 3.2): the emitter writes
 * events with zero added pause between them, so the recording sleeper observes no calls at all.
 */
class PacedSseEmitterNoPaceTest {

    /**
     * A recording sleeper: a `(Long) -> Unit` that appends every requested sleep duration to
     * [recorded]. Zero wall-clock delay — it only remembers whether (and with what) it was called.
     */
    private class RecordingSleeper {
        val recorded = mutableListOf<Long>()
        fun sleep(millis: Long) {
            recorded.add(millis)
        }
    }

    /**
     * Property 7: emitting every event in [events] with NO_DELAY and then calling end must leave
     * the recording sleeper completely untouched (zero added pause).
     */
    // Feature: streaming-sse-module, Property 7: No pacing means no added pause
    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("noPaceCases")
    fun `Property 7 NO_DELAY never invokes the sleeper for any event sequence`(
        name: String,
        events: List<String>,
    ) {
        val sleeper = RecordingSleeper()
        // A padding-free writer is allowed with NO_DELAY (delay-without-padding is only rejected
        // for a configured, non-sentinel delay).
        val writer = PaddedSseWriter(headPadBytes = 0, tailPadBytes = 0, headPadEvents = 0)
        val emitter = PacedSseEmitter(
            writer = writer,
            delayMillis = PacedSseEmitter.NO_DELAY,
            sleep = sleeper::sleep,
        )
        val out = ByteArrayOutputStream()

        for (e in events) {
            emitter.emit(out, e)
        }
        emitter.end(out)

        assertTrue(
            sleeper.recorded.isEmpty(),
            "NO_DELAY must add no pause, but sleeper was invoked with ${sleeper.recorded} (events=$events)",
        )
        // Sanity: every event was actually written, so the "no sleep" result is not a vacuous pass.
        assertEquals(events.size, emitter.eventsWritten(), "all events should be written")
    }

    companion object {
        /**
         * 10–20 diverse NO_DELAY sequences: event counts spanning 0..20, with empty, multi-byte
         * UTF-8, literal `data:`, leading `:`, embedded-newline, whitespace, and larger payloads.
         */
        @JvmStatic
        fun noPaceCases(): Stream<Arguments> = Stream.of(
            Arguments.of("empty-sequence", emptyList<String>()),
            Arguments.of("single-empty-payload", listOf("")),
            Arguments.of("single-token", listOf("hello")),
            Arguments.of("two-tokens", listOf("a", "bb")),
            Arguments.of("three-tokens", listOf("one", "two", "three")),
            Arguments.of("leading-colon", listOf(":not a comment", "x")),
            Arguments.of("literal-data-prefix", listOf("data: smuggled", "more")),
            Arguments.of("multibyte-utf8", listOf("café", "résumé", "日本語", "🚀")),
            Arguments.of("embedded-newline", listOf("first\nsecond", "third")),
            Arguments.of("whitespace", listOf("  leading", "trailing  ", "\t", " ")),
            Arguments.of("punctuation", listOf("!?.,;:", ":::", ": x")),
            Arguments.of("unicode-mixed", listOf("ok", "naïve", "Москва", "Ω≈ç√")),
            Arguments.of("five-words", listOf("The", " quick", " brown", " fox", ".")),
            Arguments.of("larger-payloads", listOf("x".repeat(3000), "y".repeat(1500))),
            Arguments.of("ten-events", (1..10).map { "t$it" }),
            Arguments.of("fifteen-events", (1..15).map { "e$it" }),
            Arguments.of("twenty-events", (1..20).map { "v$it" }),
        )
    }
}
