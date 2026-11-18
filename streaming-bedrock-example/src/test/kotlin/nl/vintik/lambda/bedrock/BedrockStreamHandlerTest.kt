package nl.vintik.lambda.bedrock

import com.amazonaws.services.lambda.runtime.Context
import io.mockk.every
import io.mockk.mockk
import nl.vintik.lambda.streaming.DELIMITER_LEN
import nl.vintik.lambda.streaming.sse.RUNTIME_API_ACCEPTED
import nl.vintik.lambda.streaming.sse.ResponseChannel
import nl.vintik.lambda.streaming.sse.StreamingResponse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import kotlin.coroutines.cancellation.CancellationException

/**
 * Unit tests for [BedrockStreamHandler]. The real model source is replaced by a fake
 * [TokenStreamSource], and the streamed response goes to a fake [ResponseChannel] instead of the
 * Lambda Runtime API, so the orchestration contract is asserted with no AWS interaction.
 *
 * Note which stream each assertion looks at. Successful output goes to the **streamed** channel —
 * the whole point of the change, since the `OutputStream` the runtime provides cannot stream.
 * Pre-commit errors (400, 502) still go to that **buffered** stream, because at that point no
 * status has been committed and a short JSON body does not need streaming.
 */
class BedrockStreamHandlerTest {

    private val context = mockk<Context>(relaxed = true).also {
        every { it.awsRequestId } returns "req-test"
    }

    private class RecordingOutputStream : OutputStream() {
        val bytes = ByteArrayOutputStream()
        var flushes = 0
        var closed = false
        override fun write(b: Int) = bytes.write(b)
        override fun write(b: ByteArray) = bytes.write(b)
        override fun write(b: ByteArray, off: Int, len: Int) = bytes.write(b, off, len)
        override fun flush() { flushes++ }
        override fun close() { closed = true }
        fun text(): String = bytes.toByteArray().decodeToString()
    }

    /** Stands in for the live Runtime API connection. */
    private class FakeChannel : ResponseChannel {
        val sink = ByteArrayOutputStream()
        var completions = 0
        override val output: OutputStream get() = sink
        override fun complete(): Int {
            completions++
            return RUNTIME_API_ACCEPTED
        }
        fun text(): String = sink.toString(Charsets.UTF_8)
    }

    private val streamed = FakeChannel()

    /** `requireAgent = false`: the agent patches the real runtime, which is absent in a unit test. */
    private fun streaming() = StreamingResponse(channels = { streamed }, requireAgent = false)

    private fun handlerWith(
        deltas: List<String>,
        resolver: RequestResolver<PromptRequest> = PromptResolver(),
    ): BedrockStreamHandler =
        BedrockStreamHandler(
            requestResolver = { resolver },
            source = { TokenStreamSource { _, onDelta -> deltas.forEach(onDelta) } },
            streaming = ::streaming,
        )

    @Test
    fun `Given deltas When handled Then the streamed response carries metadata then one data event per delta`() {
        val deltas = listOf("Hello", " streaming", " world")
        val handler = handlerWith(deltas)
        val buffered = RecordingOutputStream()
        val body = """{"prompt":"hi","maxTokens":64}"""

        handler.handleRequest(ByteArrayInputStream(body.toByteArray()), buffered, context)

        val produced = streamed.sink.toByteArray()
        val delimiter = indexOfDelimiter(produced)
        assertTrue(delimiter >= 0, "the metadata delimiter must precede the body")
        val text = produced.copyOfRange(delimiter + DELIMITER_LEN, produced.size).decodeToString()
        deltas.forEach { assertTrue(text.contains("data: $it\n\n"), "missing data event for '$it'") }
        assertTrue(text.contains("event: end\ndata: [DONE]\n\n"), "terminal [DONE] event must be present")
        assertEquals(1, streamed.completions, "the streamed response must be completed exactly once")
        assertEquals("", buffered.text(), "nothing may be written to the buffered stream on success")
    }

    @Test
    fun `Given a successful stream When handled Then no SSE padding is emitted`() {
        val handler = handlerWith(List(5) { "tok$it" })

        handler.handleRequest(ByteArrayInputStream("{}".toByteArray()), RecordingOutputStream(), context)

        val produced = streamed.sink.toByteArray()
        val body = produced
            .copyOfRange(indexOfDelimiter(produced) + DELIMITER_LEN, produced.size)
            .decodeToString()
        assertFalse(
            body.contains("\n: "),
            "padding is obsolete: a real stream needs no SSE-comment filler",
        )
        // 5 deltas plus the terminal event. Padded, the same body was tens of kilobytes.
        assertTrue(body.length < 150, "the body must be the answer, not filler: ${body.length} bytes")
    }

    @Test
    fun `Given a resolver error When handled Then a buffered error response is written and no model call happens`() {
        val resolver = RequestResolver<PromptRequest> { RequestResult.Error(400, "bad request") }
        var sourceCalled = false
        val handler = BedrockStreamHandler(
            requestResolver = { resolver },
            source = { TokenStreamSource { _, _ -> sourceCalled = true } },
            streaming = ::streaming,
        )
        val buffered = RecordingOutputStream()

        handler.handleRequest(ByteArrayInputStream("{}".toByteArray()), buffered, context)

        val produced = buffered.text()
        assertTrue(produced.contains("\"statusCode\":400"), "status 400 must be committed")
        assertTrue(produced.contains("bad request"), "error message must be propagated")
        assertFalse(sourceCalled, "the model source must not be invoked on a resolver error")
        assertTrue(buffered.closed, "the buffered stream must be closed")
        assertEquals("", streamed.text(), "no streamed response may be opened for a pre-commit error")
    }

    @Test
    fun `Given the source fails before any delta When handled Then a buffered 502 is written and no 200 is committed`() {
        val handler = BedrockStreamHandler(
            requestResolver = { PromptResolver() },
            source = { TokenStreamSource { _, _ -> error("bedrock unavailable") } },
            streaming = ::streaming,
        )
        val buffered = RecordingOutputStream()

        handler.handleRequest(ByteArrayInputStream("{}".toByteArray()), buffered, context)

        val produced = buffered.text()
        assertTrue(produced.contains("\"statusCode\":502"), "status 502 must be committed, got: ${produced.take(120)}")
        assertFalse(produced.contains("event: end"), "no SSE terminal event on a pre-first-delta failure")
        assertTrue(buffered.closed, "the buffered stream must be closed")
    }

    @Test
    fun `Given the source is cancelled When handled Then cancellation propagates and no 502 is written`() {
        val handler = BedrockStreamHandler(
            requestResolver = { PromptResolver() },
            source = { TokenStreamSource { _, _ -> throw CancellationException("cancelled") } },
            streaming = ::streaming,
        )
        val buffered = RecordingOutputStream()

        assertThrows(CancellationException::class.java) {
            handler.handleRequest(ByteArrayInputStream("{}".toByteArray()), buffered, context)
        }

        assertFalse(
            buffered.text().contains("\"statusCode\":502"),
            "cancellation must not be mapped to an HTTP error",
        )
    }

    @Test
    fun `Given a successful generation with zero deltas When handled Then 200 and a terminal event are still streamed`() {
        val handler = BedrockStreamHandler(
            requestResolver = { PromptResolver() },
            source = { TokenStreamSource { _, _ -> /* no deltas */ } },
            streaming = ::streaming,
        )

        handler.handleRequest(ByteArrayInputStream("{}".toByteArray()), RecordingOutputStream(), context)

        val produced = streamed.sink.toByteArray()
        assertTrue(indexOfDelimiter(produced) >= 0, "the 200 metadata delimiter must be present")
        val text = produced.decodeToString()
        assertTrue(text.contains("\"statusCode\":200"), "200 must be committed for a successful empty generation")
        assertTrue(text.contains("event: end\ndata: [DONE]\n\n"), "terminal [DONE] event must be present")
    }

    @Test
    fun `Given a failure after the first delta When handled Then it propagates rather than rewriting the committed status`() {
        val handler = BedrockStreamHandler(
            requestResolver = { PromptResolver() },
            source = {
                TokenStreamSource { _, onDelta ->
                    onDelta("partial")
                    error("connection dropped mid-generation")
                }
            },
            streaming = ::streaming,
        )
        val buffered = RecordingOutputStream()

        assertThrows(IllegalStateException::class.java) {
            handler.handleRequest(ByteArrayInputStream("{}".toByteArray()), buffered, context)
        }

        assertTrue(streamed.text().contains("data: partial"), "the delivered prefix must remain")
        assertFalse(
            buffered.text().contains("\"statusCode\":502"),
            "the status was already committed, so it must not be rewritten",
        )
    }

    private fun indexOfDelimiter(data: ByteArray): Int {
        var run = 0
        for (i in data.indices) {
            if (data[i].toInt() == 0) {
                run++
                if (run == DELIMITER_LEN) return i - DELIMITER_LEN + 1
            } else {
                run = 0
            }
        }
        return -1
    }
}
