package nl.vintik.lambda.streaming.sse

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.io.ByteArrayOutputStream
import java.util.stream.Stream

/**
 * Property-based round-trip tests for [PaddedSseWriter] (Feature: streaming-sse-module, Property 1).
 *
 * These tests pin the *actual* byte behaviour of the promoted writer against a faithful model of
 * the client-side filter `sed -n 's/^data: //p'`. The writer emits each event as the literal bytes
 * `data: <text>\n\n` (plus an optional `: <padding>\n\n` comment line). The filter therefore sees
 * one `data: ` line per event and, for that line, re-emits the remainder followed by a newline.
 *
 * Consequence (verified against real `sed`): for single-line payloads the filter output equals the
 * original texts each followed by `\n`. For a payload that itself contains an embedded `\n`, only
 * the segment up to the first newline is preceded by `data: `; the remainder lands on a line the
 * filter ignores, so such a payload does NOT round-trip byte-identically. That divergence is a real
 * property of the promoted writer (it does not prefix continuation lines with `data: `) and is
 * asserted here as true behaviour rather than hidden — the writer is intentionally left unchanged.
 */
class PaddedSseWriterRoundTripTest {

    /**
     * Faithful model of `sed -n 's/^data: //p'`: split the produced bytes into lines on `\n`, keep
     * only lines beginning with the literal `data: ` prefix, strip exactly that prefix, and re-emit
     * each kept line followed by a single `\n` (sed prints one line per match, terminated by `\n`).
     */
    private fun sedDataFilter(produced: String): String {
        val sb = StringBuilder()
        for (line in produced.split("\n")) {
            if (line.startsWith(DATA_PREFIX)) {
                sb.append(line.removePrefix(DATA_PREFIX)).append('\n')
            }
        }
        return sb.toString()
    }

    /** Writes [events] with the given tier, returning everything up to (but excluding) the terminal. */
    private fun writeNonTerminal(events: List<String>, headPad: Int, tailPad: Int, headPadEvents: Int): String {
        val writer = PaddedSseWriter(headPadBytes = headPad, tailPadBytes = tailPad, headPadEvents = headPadEvents)
        val out = ByteArrayOutputStream()
        for (e in events) {
            writer.writeEvent(out, e)
        }
        // Deliberately do NOT call writeEnd() — Property 1 is the stream excluding the terminal event.
        return out.toByteArray().decodeToString()
    }

    /**
     * Property 1: for single-line payloads, applying the `sed -n 's/^data: //p'` filter to the
     * output excluding the terminal event reproduces the concatenation of the original event texts
     * (each event is one `data: ` line, so the faithful filter output is each text followed by `\n`).
     */
    // Feature: streaming-sse-module, Property 1: Payload round-trips through the padded stream (excluding terminal)
    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("singleLineRoundTripCases")
    fun `Property 1 single-line payloads round-trip through the padded stream excluding terminal`(
        name: String,
        events: List<String>,
        headPad: Int,
        tailPad: Int,
        headPadEvents: Int,
    ) {
        val produced = writeNonTerminal(events, headPad, tailPad, headPadEvents)

        val recovered = sedDataFilter(produced)

        // The filter emits one line per event; byte-identical to each original text + "\n".
        val expected = events.joinToString("") { it + "\n" }
        assertEquals(expected, recovered, "padding must never corrupt the recoverable payload (events=$events)")
    }

    /**
     * Property 1 — embedded-newline boundary. The promoted writer emits `data: <text>\n\n` literally
     * and does NOT prefix continuation lines with `data: `, so a payload containing an embedded
     * newline is only partially recovered: the filter keeps the segment up to the first `\n` and
     * drops the rest. This asserts the writer's true behaviour (reported finding), confirming the
     * round-trip claim holds only for single-line payloads.
     */
    // Feature: streaming-sse-module, Property 1: Payload round-trips through the padded stream (excluding terminal)
    @ParameterizedTest(name = "[{index}] headPad={1}")
    @MethodSource("embeddedNewlinePads")
    fun `Property 1 embedded-newline payload does not round-trip byte-identically through the writer`(
        headPad: Int,
        tailPad: Int,
    ) {
        val event = "first\nsecond"
        val produced = writeNonTerminal(listOf(event), headPad, tailPad, 1)

        val recovered = sedDataFilter(produced)

        // Only the pre-newline segment survives as a `data: ` line; "second" is on an ignored line.
        assertEquals("first\n", recovered, "only the segment before the embedded newline is filter-visible")
        // Explicitly document the divergence: the embedded-newline payload is NOT recovered intact.
        assertNotEquals(event + "\n", recovered, "embedded newline payload cannot round-trip through this writer")
    }

    companion object {
        private const val DATA_PREFIX = "data: "

        /**
         * 10–20 diverse single-line cases: 1–20 events, payloads from 0 bytes up to a few KB, across
         * head/tail tiers (including zero padding and >100 KB head padding). Includes the required
         * corpus cases (Requirements 4.5–4.6): a leading `:`, the literal `data:`, multi-byte UTF-8,
         * an empty payload, and an empty sequence. (Embedded-newline is covered separately above,
         * because it provably does not round-trip through the promoted writer.)
         */
        @JvmStatic
        fun singleLineRoundTripCases(): Stream<Arguments> = Stream.of(
            // Boundary: empty sequence — no events, filter yields nothing.
            Arguments.of("empty-sequence", emptyList<String>(), 0, 0, 0),
            // Boundary: single empty payload.
            Arguments.of("empty-payload", listOf(""), 1024, 128, 1),
            // Required: leading ':' must survive (the data: prefix protects it from the filter).
            Arguments.of("leading-colon", listOf(":not a comment"), 2048, 256, 1),
            // Required: literal "data:" inside the payload (sed strips only the first ^data: ).
            Arguments.of("literal-data-prefix", listOf("data: smuggled"), 4096, 512, 1),
            // Required: multi-byte UTF-8 payload.
            Arguments.of("multibyte-utf8", listOf("café résumé 日本語 🚀"), 2048, 256, 2),
            // Single simple token, no padding at all.
            Arguments.of("single-no-pad", listOf("hello"), 0, 0, 0),
            // A few events, head tier larger than tail tier.
            Arguments.of("three-events-tiered", listOf("a", "bb", "ccc"), 100 * 1024, 10 * 1024, 2),
            // Mixed content across several events.
            Arguments.of(
                "mixed-five",
                listOf("The", " quick", " brown", " fox", "."),
                8192, 1024, 3,
            ),
            // Payload with internal spaces and punctuation.
            Arguments.of("spaces-and-punct", listOf("  leading", "trailing  ", "!?.,;:"), 512, 64, 1),
            // Larger payloads (a few KB each).
            Arguments.of("few-kb", listOf("x".repeat(3000), "y".repeat(1500)), 65536, 4096, 1),
            // Tail-only padding (headPadEvents = 0 means every event uses the tail tier).
            Arguments.of("tail-only", listOf("one", "two", "three", "four"), 100 * 1024, 2048, 0),
            // Twenty tiny events — upper bound of the event count range.
            Arguments.of("twenty-events", (1..20).map { "t$it" }, 32768, 4096, 3),
            // Colon-heavy payloads that are not comment lines (they are data lines).
            Arguments.of("colons", listOf(":::", ": x", ":"), 1024, 128, 1),
            // Unicode mixed with ASCII across events.
            Arguments.of("unicode-mixed", listOf("ok", "naïve", "Москва", "Ω≈ç√"), 16384, 2048, 2),
        )

        /** Diverse tiers for the embedded-newline divergence case. */
        @JvmStatic
        fun embeddedNewlinePads(): Stream<Arguments> = Stream.of(
            Arguments.of(0, 0),
            Arguments.of(1024, 128),
            Arguments.of(100 * 1024, 10 * 1024),
        )
    }
}
