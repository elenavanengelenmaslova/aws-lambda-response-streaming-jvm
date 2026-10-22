package nl.vintik.lambda.bedrock

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.stream.Stream

/**
 * Unit + property tests for [PaddedSseWriter] — the byte-level piece of the Bedrock example,
 * analogous to the S3 example's ResponseWriter/copy tests.
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
        val sizes = (1..4).map { writer.writeEvent(ByteArrayOutputStream().let { CountingFlushStream() }, "t$it") }

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
        assertTrue(!out.buf.toByteArray().decodeToString().contains("\n: "), "no padding comment expected")
    }

    @Test
    fun `Given writeEnd When called Then it emits the terminal DONE event`() {
        val writer = PaddedSseWriter(0, 0, 0)
        val out = CountingFlushStream()
        writer.writeEnd(out)
        assertEquals("event: end\ndata: [DONE]\n\n", out.buf.toByteArray().decodeToString())
    }

    @Test
    fun `Given negative padding When constructed Then it is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { PaddedSseWriter(-1, 0, 0) }
        assertThrows(IllegalArgumentException::class.java) { PaddedSseWriter(0, -1, 0) }
        assertThrows(IllegalArgumentException::class.java) { PaddedSseWriter(0, 0, -1) }
    }

    /**
     * Property: for any token and any padding tier, the real token text is always recoverable by
     * stripping the `data: ` prefix from the first line and dropping the `:` padding comment — i.e.
     * the client-side filter (`sed -n 's/^data: //p'`) always yields exactly the token back.
     */
    @ParameterizedTest
    @MethodSource("tokenAndPadCases")
    fun `Property the data line always round-trips to the original token regardless of padding`(
        token: String,
        headPad: Int,
        tailPad: Int,
    ) {
        val writer = PaddedSseWriter(headPadBytes = headPad, tailPadBytes = tailPad, headPadEvents = 1)
        val out = CountingFlushStream()
        writer.writeEvent(out, token)

        val produced = out.buf.toByteArray().decodeToString()
        // Mirror `sed -n 's/^data: //p'`: take lines starting with "data: ", strip the prefix.
        val recovered = produced.lineSequence()
            .filter { it.startsWith("data: ") }
            .joinToString("\n") { it.removePrefix("data: ") }
        assertEquals(token, recovered, "padding must never corrupt the recoverable token text")
        // Padding, when present, is on a line the filter ignores (starts with ':').
        if (headPad > 0) {
            assertTrue(produced.contains("\n: "), "head padding comment should be present")
        }
    }

    companion object {
        @JvmStatic
        fun tokenAndPadCases(): Stream<Arguments> = Stream.of(
            Arguments.of("hello", 0, 0),
            Arguments.of("Cloud computing", 1024, 128),
            Arguments.of("multi word token here", 100 * 1024, 10 * 1024),
            Arguments.of("unicode: café résumé 日本語", 2048, 256),
            Arguments.of("punctuation!?.,;:", 512, 64),
            Arguments.of("a", 1, 0),
            Arguments.of("   leading spaces", 4096, 512),
            Arguments.of("trailing spaces   ", 4096, 512),
            Arguments.of("tabs\tand\tstuff", 8192, 1024),
            Arguments.of("1234567890", 65536, 4096),
            Arguments.of("AWS Lambda", 16384, 2048),
            Arguments.of("x".repeat(500), 32768, 4096),
        )
    }
}
