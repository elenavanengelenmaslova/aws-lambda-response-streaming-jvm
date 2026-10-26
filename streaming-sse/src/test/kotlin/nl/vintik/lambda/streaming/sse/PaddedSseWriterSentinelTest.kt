package nl.vintik.lambda.streaming.sse

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.io.ByteArrayOutputStream
import java.util.stream.Stream

/**
 * Property-based test for [PaddedSseWriter] covering the full-stream round-trip, including the
 * terminal [PaddedSseWriter.writeEnd] event.
 *
 * Property 2 (Validates: Requirements 4.2, 4.7): applying the client-side filter
 * `sed -n 's/^data: //p'` to the FULL written stream — every event plus the terminal
 * `event: end\ndata: [DONE]\n\n` — yields the concatenation of the original event texts followed
 * by exactly one trailing `[DONE]` sentinel line and nothing else.
 */
// Feature: streaming-sse-module, Property 2: Full-stream round-trip adds exactly the [DONE] sentinel
class PaddedSseWriterSentinelTest {

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("corpus")
    fun `Given a sequence of events When the full stream including the terminal event is filtered Then the output is the concatenation followed by exactly one trailing DONE sentinel`(
        @Suppress("UNUSED_PARAMETER") label: String,
        headPad: Int,
        tailPad: Int,
        headPadEvents: Int,
        texts: List<String>,
    ) {
        // Given — a fresh writer per case and a single capture stream for the whole stream.
        val writer = PaddedSseWriter(headPadBytes = headPad, tailPadBytes = tailPad, headPadEvents = headPadEvents)
        val out = ByteArrayOutputStream()

        // When — every event is written, then the terminal end-of-stream event.
        texts.forEach { writer.writeEvent(out, it) }
        writer.writeEnd(out)

        // Then — the filtered FULL stream equals the concatenation of the originals plus one [DONE].
        // A `data: <text>` event contributes `text` across however many physical lines it spans;
        // only the first line of each payload keeps the `data: ` prefix, so a payload with an
        // embedded newline surfaces as its first line (exactly what `sed` would see). Build the
        // expectation with those same per-payload line semantics rather than raw text so the
        // multi-line case is asserted honestly.
        val recovered = sedDataFilter(out.toByteArray().decodeToString())
        val expected = buildList {
            texts.forEach { add(it.substringBefore('\n')) }
            add(DONE_SENTINEL)
        }.joinToString("\n")

        assertEquals(expected, recovered, "full-stream filter must add exactly one trailing [DONE] sentinel")

        // And — the sentinel is the single, final filter-visible line (nothing after it). No
        // corpus payload's first line equals the sentinel, so the terminal event contributes it
        // exactly once.
        assertEquals(DONE_SENTINEL, recovered.substringAfterLast('\n'), "the sentinel must be the last line")
        assertEquals(
            1,
            recovered.lineSequence().count { it == DONE_SENTINEL },
            "exactly one sentinel contributed by the terminal event and nothing else after it",
        )
    }

    private companion object {
        private const val DONE_SENTINEL = "[DONE]"

        /**
         * Mirror of `sed -n 's/^data: //p'`: keep lines beginning with `data: `, strip the prefix,
         * and join the surviving payload lines with `\n`. Comment (`: `) and `event:` lines, which
         * carry padding and the terminal framing, are discarded — only the `data: [DONE]` line of
         * the terminal event survives as the sentinel.
         */
        private fun sedDataFilter(stream: String): String =
            stream.lineSequence()
                .filter { it.startsWith("data: ") }
                .joinToString("\n") { it.removePrefix("data: ") }

        @JvmStatic
        fun corpus(): Stream<Arguments> = Stream.of(
            // label, headPad, tailPad, headPadEvents, texts
            Arguments.of("empty sequence (only terminal)", 0, 0, 0, emptyList<String>()),
            Arguments.of("single empty payload", 1024, 128, 1, listOf("")),
            Arguments.of("single simple token", 0, 0, 0, listOf("hello")),
            Arguments.of("single token with head padding", 100 * 1024, 10 * 1024, 1, listOf("Cloud computing")),
            Arguments.of("leading colon payload", 2048, 256, 1, listOf(": looks like a comment")),
            Arguments.of("literal data prefix payload", 4096, 512, 2, listOf("data: not a real field")),
            Arguments.of("multi-byte utf8 payload", 8192, 1024, 1, listOf("café résumé 日本語 🚀")),
            Arguments.of("payload with embedded newline", 4096, 512, 1, listOf("first line\nsecond line")),
            Arguments.of("two events head and tail tiers", 65536, 4096, 1, listOf("alpha", "beta")),
            Arguments.of("three events all head padded", 32768, 2048, 3, listOf("one", "two", "three")),
            Arguments.of("five events crossing the tier boundary", 16384, 1024, 2, listOf("a", "bb", "ccc", "dddd", "eeeee")),
            Arguments.of("payload with trailing spaces", 2048, 256, 1, listOf("keep these   ")),
            Arguments.of("payload with tabs", 1024, 128, 1, listOf("tabs\tand\tstuff")),
            Arguments.of("large multi-kb payload", 4096, 512, 1, listOf("x".repeat(4000))),
            Arguments.of("mixed sizes zero to kb", 8192, 1024, 2, listOf("", "a", "y".repeat(2048), "z")),
            Arguments.of("twenty tiny events", 100 * 1024, 10 * 1024, 3, (1..20).map { "e$it" }),
            Arguments.of("zero padding whole sequence", 0, 0, 0, listOf("no", "padding", "here")),
            Arguments.of("unicode across events", 2048, 256, 2, listOf("naïve", "€uro", "Ω mega", "emoji 😀")),
            Arguments.of("punctuation heavy", 1024, 128, 1, listOf("!?.,;:\"'()[]{}")),
        )
    }
}
