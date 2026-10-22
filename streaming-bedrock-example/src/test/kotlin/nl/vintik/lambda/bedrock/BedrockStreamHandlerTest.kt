package nl.vintik.lambda.bedrock

import com.amazonaws.services.lambda.runtime.Context
import io.mockk.mockk
import nl.vintik.lambda.streaming.DELIMITER_LEN
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream

/**
 * Unit tests for [BedrockStreamHandler], mirroring the S3 example's StreamHandler tests: the real
 * model source is replaced by a fake [TokenStreamSource] so the orchestration contract is asserted
 * with no AWS interaction.
 */
class BedrockStreamHandlerTest {

    private val context = mockk<Context>(relaxed = true)

    private class RecordingOutputStream : OutputStream() {
        val bytes = ByteArrayOutputStream()
        var flushes = 0
        var closed = false
        override fun write(b: Int) = bytes.write(b)
        override fun write(b: ByteArray) = bytes.write(b)
        override fun write(b: ByteArray, off: Int, len: Int) = bytes.write(b, off, len)
        override fun flush() { flushes++ }
        override fun close() { closed = true }
    }

    private fun handlerWith(
        deltas: List<String>,
        resolver: RequestResolver<PromptRequest> = PromptResolver(),
    ): BedrockStreamHandler =
        BedrockStreamHandler(
            requestResolver = { resolver },
            source = { TokenStreamSource { _, onDelta -> deltas.forEach(onDelta) } },
        )

    @Test
    fun `Given deltas When handled Then metadata precedes every data event and stream closes`() {
        // Given — no padding so the body is easy to assert.
        val deltas = listOf("Hello", " streaming", " world")
        val handler = handlerWith(deltas)
        val out = RecordingOutputStream()
        val body = """{"prompt":"hi","headPadBytes":0,"tailPadBytes":0,"maxTokens":64}"""

        // When
        handler.handleRequest(ByteArrayInputStream(body.toByteArray()), out, context)

        // Then — metadata delimiter present before any SSE content.
        val produced = out.bytes.toByteArray()
        val delim = indexOfDelimiter(produced)
        assertTrue(delim >= 0, "metadata delimiter must be present")
        val text = produced.copyOfRange(delim + DELIMITER_LEN, produced.size).decodeToString()
        deltas.forEach { assertTrue(text.contains("data: $it\n\n"), "missing data event for '$it'") }
        assertTrue(text.contains("event: end\ndata: [DONE]\n\n"), "terminal [DONE] event must be present")
        assertTrue(out.closed, "output stream must be closed")
    }

    @Test
    fun `Given a resolver error When handled Then a metadata-only error response is written and no deltas`() {
        // Given — a resolver that always errors; a source that must NOT be called.
        val resolver = RequestResolver<PromptRequest> { RequestResult.Error(400, "bad request") }
        var sourceCalled = false
        val handler = BedrockStreamHandler(
            requestResolver = { resolver },
            source = { TokenStreamSource { _, _ -> sourceCalled = true } },
        )
        val out = RecordingOutputStream()

        // When
        handler.handleRequest(ByteArrayInputStream("{}".toByteArray()), out, context)

        // Then
        val produced = out.bytes.toByteArray().decodeToString()
        assertTrue(produced.contains("\"message\""), "error body must carry a message")
        assertTrue(produced.contains("bad request"), "error message must be propagated")
        assertTrue(!sourceCalled, "model source must not be invoked on a resolver error")
        assertTrue(out.closed, "output stream must be closed")
    }

    @Test
    fun `Given tiered padding When handled Then head events carry larger padding than tail events`() {
        // Given — 5 deltas, head pad 2000 for first 2, tail pad 100.
        val deltas = List(5) { "tok$it" }
        val handler = handlerWith(deltas)
        val out = RecordingOutputStream()
        val body = """{"prompt":"p","headPadBytes":2000,"tailPadBytes":100,"headPadEvents":2,"maxTokens":64}"""

        // When
        handler.handleRequest(ByteArrayInputStream(body.toByteArray()), out, context)

        // Then — the produced bytes contain SSE comment padding.
        val produced = out.bytes.toByteArray().decodeToString()
        assertTrue(produced.contains("\n: "), "SSE comment padding must be present")
        // Head padding (2000 spaces) must appear; a run that long only comes from head tier.
        assertTrue(produced.contains(" ".repeat(2000)), "head-tier padding run must be present")
    }

    private fun indexOfDelimiter(data: ByteArray): Int {
        var run = 0
        for (i in data.indices) {
            if (data[i].toInt() == 0) {
                run++
                if (run == DELIMITER_LEN) return i - DELIMITER_LEN + 1
            } else run = 0
        }
        return -1
    }
}
