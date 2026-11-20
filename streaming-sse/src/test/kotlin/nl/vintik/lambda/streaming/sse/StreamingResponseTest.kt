package nl.vintik.lambda.streaming.sse

import nl.vintik.lambda.streaming.ResponseMetadata
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream

/**
 * [StreamingResponse] is exercised through a fake [ResponseChannel], so the ordering rules are
 * verified without a Lambda runtime: the prelude is written before the body, the invocation is
 * marked answered only after the Runtime API accepts the response, and a failure mid-body still
 * completes the request rather than leaving the client hanging.
 *
 * `requireAgent = false` throughout except where the agent check itself is under test — the agent's
 * patched-ness is process-global, and these tests must not depend on it.
 */
class StreamingResponseTest {

    private val metadata = ResponseMetadata(200, mapOf("Content-Type" to "text/event-stream"))

    /** Records what was written and what status the Runtime API returned. */
    private class FakeChannel(
        private val status: Int = RUNTIME_API_ACCEPTED,
        private val completeFailure: Throwable? = null,
    ) : ResponseChannel {
        val sink = ByteArrayOutputStream()
        var completions = 0
        override val output: OutputStream get() = sink
        override fun complete(): Int {
            completions++
            completeFailure?.let { throw it }
            return status
        }

        fun body(): String = sink.toString(Charsets.UTF_8)
    }

    @AfterEach
    fun clear() = StreamingState.reset()

    @Test
    fun `Given a streamed response When it completes Then the prelude precedes the body and the invocation is marked answered`() {
        val channel = FakeChannel()
        val response = StreamingResponse(channels = { channel }, requireAgent = false)

        val returned = response.send("req-1", metadata) { live ->
            live.write("payload".toByteArray())
            "done"
        }

        assertEquals("done", returned)
        val body = channel.body()
        assertTrue(body.startsWith("{"), "the metadata prelude must be written first, got: $body")
        assertTrue(body.endsWith("payload"), "the body must follow the prelude, got: $body")
        // The 8 NUL bytes separate prelude from body.
        assertTrue(body.contains("\u0000".repeat(8)), "the 8-byte delimiter must be present")
        assertEquals(1, channel.completions)
        assertTrue(StreamingState.alreadyAnswered())
    }

    @Test
    fun `Given a blank request id When sending Then it is rejected before anything is written`() {
        val channel = FakeChannel()
        val response = StreamingResponse(channels = { channel }, requireAgent = false)

        assertThrows(IllegalArgumentException::class.java) {
            response.send("  ", metadata) { }
        }

        assertEquals("", channel.body())
        assertEquals(0, channel.completions)
    }

    @Test
    fun `Given the agent is not loaded When sending with the agent required Then it fails fast rather than streaming blind`() {
        val channel = FakeChannel()
        val response = StreamingResponse(channels = { channel }, requireAgent = true)

        val failure = assertThrows(IllegalStateException::class.java) {
            response.send("req-2", metadata) { }
        }

        assertTrue(
            failure.message?.contains("-javaagent") == true,
            "the error must say how to fix it, got: ${failure.message}",
        )
        assertEquals(0, channel.completions)
    }

    @Test
    fun `Given the Runtime API rejects the response When sending Then it raises and does not mark the invocation answered`() {
        val channel = FakeChannel(status = 400)
        val response = StreamingResponse(channels = { channel }, requireAgent = false)

        val failure = assertThrows(IllegalStateException::class.java) {
            response.send("req-3", metadata) { }
        }

        assertTrue(failure.message?.contains("400") == true)
        assertFalse(
            StreamingState.alreadyAnswered(),
            "a rejected response must not suppress AWS's own post",
        )
    }

    @Test
    fun `Given the body fails midway When sending Then the request is completed and the original failure propagates`() {
        val channel = FakeChannel()
        val response = StreamingResponse(channels = { channel }, requireAgent = false)

        val failure = assertThrows(IOException::class.java) {
            response.send("req-4", metadata) { live ->
                live.write("partial".toByteArray())
                throw IOException("source died")
            }
        }

        assertEquals("source died", failure.message)
        assertEquals(1, channel.completions, "the request must be closed so the client is not left hanging")
        assertFalse(StreamingState.alreadyAnswered())
    }

    @Test
    fun `Given completing also fails after a body failure When sending Then the body failure wins and the completion failure is suppressed`() {
        val channel = FakeChannel(completeFailure = IOException("connection reset"))
        val response = StreamingResponse(channels = { channel }, requireAgent = false)

        val failure = assertThrows(IllegalArgumentException::class.java) {
            response.send("req-5", metadata) { throw IllegalArgumentException("bad prompt") }
        }

        assertEquals("bad prompt", failure.message)
        assertEquals(1, failure.suppressed.size)
        assertEquals("connection reset", failure.suppressed.first().message)
    }

    @Test
    fun `Given a previous invocation marked answered When a new response starts Then the flag is cleared before anything is posted`() {
        StreamingState.markAnswered()
        val channel = FakeChannel(status = 400)
        val response = StreamingResponse(channels = { channel }, requireAgent = false)

        assertThrows(IllegalStateException::class.java) {
            response.send("req-6", metadata) { }
        }

        assertFalse(
            StreamingState.alreadyAnswered(),
            "a reused environment must not carry an answered flag into the next invocation",
        )
    }
}
