package nl.vintik.lambda.streaming.sse

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.io.ByteArrayOutputStream

/**
 * Property-based test for [PaddedSseWriter].
 *
 * Feature: streaming-sse-module, Property 3: Padding lives only in comment lines
 *
 * Validates: Requirements 4.3, 4.7
 *
 * For any event sequence and any padding tier, every padding byte appears only within a `: `
 * SSE comment line, and no `data:`/filter-visible line is affected by padding. Concretely:
 *  - the filter-visible output (the `sed -n 's/^data: //p'` projection) is byte-identical whether
 *    padding is applied or not — i.e. padding never touches a `data: ` line;
 *  - every padding space byte lives on a line beginning with `: `, and the number of padding
 *    spaces on those comment lines equals exactly the configured per-event padding total.
 */
class PaddedSseWriterPaddingLocationTest {

    /** Writes [texts] through a [PaddedSseWriter] built with the given tier and returns the UTF-8 output. */
    private fun render(texts: List<String>, headPad: Int, tailPad: Int, headEvents: Int): String {
        val writer = PaddedSseWriter(headPadBytes = headPad, tailPadBytes = tailPad, headPadEvents = headEvents)
        val out = ByteArrayOutputStream()
        texts.forEach { writer.writeEvent(out, it) }
        return out.toByteArray().decodeToString()
    }

    /** Applies the `sed -n 's/^data: //p'` filter: keep lines starting with `data: `, strip the prefix. */
    private fun dataFilter(output: String): List<String> =
        output.split("\n").filter { it.startsWith("data: ") }.map { it.removePrefix("data: ") }

    /** Total count of padding space bytes carried on `: ` comment lines. */
    private fun paddingSpacesOnCommentLines(output: String): Int =
        output.split("\n")
            .filter { it.startsWith(": ") }
            .sumOf { it.removePrefix(": ").count { ch -> ch == ' ' } }

    /** Expected total per-event padding for a sequence of [n] events under the given tier. */
    private fun expectedPaddingTotal(n: Int, headPad: Int, tailPad: Int, headEvents: Int): Int =
        (0 until n).sumOf { idx -> if (idx < headEvents) headPad else tailPad }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("corpus")
    fun `Given a padded stream When inspecting lines Then all padding lives only on comment lines and no data line is affected`(
        label: String,
        texts: List<String>,
        headPad: Int,
        tailPad: Int,
        headEvents: Int,
    ) {
        // Given — the same event sequence rendered with the padding tier and with no padding at all.
        val padded = render(texts, headPad, tailPad, headEvents)
        val unpadded = render(texts, headPad = 0, tailPad = 0, headEvents = 0)

        // Then — the filter-visible (data:) projection is byte-identical with or without padding:
        // padding never alters a data line.
        assertEquals(
            dataFilter(unpadded),
            dataFilter(padded),
            "padding must not change any data: line (case: $label)",
        )

        // And every padding space byte lives on a `: ` comment line, with the exact configured total.
        val expectedPad = expectedPaddingTotal(texts.size, headPad, tailPad, headEvents)
        assertEquals(
            expectedPad,
            paddingSpacesOnCommentLines(padded),
            "all padding spaces must appear on `: ` comment lines (case: $label)",
        )

        // And no `data: ` line itself contains any trailing padding run: each data line equals
        // exactly `data: <segment>` for the segments the texts contribute (no spaces appended by padding).
        val expectedDataLines = buildExpectedDataLines(texts)
        val actualDataLines = padded.split("\n").filter { it.startsWith("data: ") }
        assertEquals(
            expectedDataLines,
            actualDataLines,
            "each data: line must be exactly the payload segment with no padding appended (case: $label)",
        )

        // And the padded byte length equals the unpadded length plus the padding block bytes only:
        // padding adds nothing beyond its own `: <spaces>\n\n` comment blocks.
        val commentBlocks = texts.indices.count { idx ->
            val pad = if (idx < headEvents) headPad else tailPad
            pad > 0
        }
        val expectedPaddedLen = unpadded.toByteArray(Charsets.UTF_8).size +
            expectedPad + commentBlocks * (": ".length + "\n\n".length)
        assertTrue(
            padded.toByteArray(Charsets.UTF_8).size == expectedPaddedLen,
            "padded size must exceed unpadded only by the padding comment blocks (case: $label)",
        )
    }

    /**
     * Builds the expected set of physical `data: ` lines. A `data: ` line is only produced for the
     * first physical line of each event text (embedded newlines split an event into a `data: ` line
     * followed by raw continuation lines that do NOT start with `data: `).
     */
    private fun buildExpectedDataLines(texts: List<String>): List<String> =
        texts.map { "data: " + it.substringBefore("\n") }

    companion object {
        /**
         * Corpus of diverse cases: varied event counts (0–20), payload sizes (0 bytes to a few KB),
         * and padding tiers (zero, tail-only, and head+tail). Includes the required adversarial
         * payloads: leading `:`, literal `data:`, embedded newline, multi-byte UTF-8, empty payload,
         * and the empty-sequence boundary.
         */
        @JvmStatic
        fun corpus(): List<Arguments> {
            val kb = "x".repeat(2048)
            return listOf(
                Arguments.of("empty sequence", emptyList<String>(), 100, 10, 3),
                Arguments.of("single empty payload", listOf(""), 100, 10, 3),
                Arguments.of("single simple payload", listOf("hello"), 100, 10, 3),
                Arguments.of("no padding at all", listOf("a", "b", "c"), 0, 0, 0),
                Arguments.of("tail padding only", listOf("a", "b", "c", "d"), 0, 64, 0),
                Arguments.of("leading colon payload", listOf(":lead", "::two"), 128, 16, 1),
                Arguments.of("literal data colon payload", listOf("data: smuggled", "x data: y"), 128, 16, 1),
                Arguments.of("embedded newline payload", listOf("line1\nline2", "a\nb\nc"), 128, 16, 1),
                Arguments.of("multi-byte utf8 payload", listOf("héllo — 世界 😀", "ßøµ"), 256, 32, 2),
                Arguments.of("mixed adversarial", listOf(":x", "data:y", "p\nq", "zß"), 200, 20, 2),
                Arguments.of("few-kb payload", listOf(kb, kb), 512, 64, 1),
                Arguments.of("head boundary exactly headEvents", listOf("1", "2", "3"), 300, 30, 3),
                Arguments.of("one past head boundary", listOf("1", "2", "3", "4"), 300, 30, 3),
                Arguments.of("zero head events tail everywhere", listOf("a", "b"), 500, 40, 0),
                Arguments.of("twenty events", (1..20).map { "e$it" }, 256, 24, 3),
                Arguments.of("single char payloads", listOf("a", "b", "c", "d", "e"), 80, 8, 2),
                Arguments.of("whitespace-containing payload", listOf("a b  c", "  lead"), 128, 16, 1),
                Arguments.of("head larger than sequence", listOf("only"), 1024, 64, 10),
            )
        }
    }
}
