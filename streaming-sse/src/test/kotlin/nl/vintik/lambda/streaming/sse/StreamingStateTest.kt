package nl.vintik.lambda.streaming.sse

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * [StreamingState] decides whether the patched runtime method suppresses its post, so the two
 * properties that matter are: a cleared flag never suppresses a legitimate response, and one
 * invocation's flag is invisible to another running concurrently.
 */
class StreamingStateTest {

    @AfterEach
    fun clear() = StreamingState.reset()

    @Test
    fun `Given a fresh invocation When nothing has been answered Then the flag is clear so the runtime still posts`() {
        StreamingState.reset()

        assertFalse(StreamingState.alreadyAnswered())
    }

    @Test
    fun `Given a streamed response When it is marked answered Then the flag is set so the runtime skips its post`() {
        StreamingState.markAnswered()

        assertTrue(StreamingState.alreadyAnswered())
    }

    @Test
    fun `Given an answered invocation When a reused environment resets Then the flag no longer suppresses the next response`() {
        StreamingState.markAnswered()

        StreamingState.reset()

        assertFalse(StreamingState.alreadyAnswered())
    }

    @Test
    fun `Given one thread marks answered When another thread checks Then it sees its own clear flag`() {
        StreamingState.markAnswered()
        val executor = Executors.newSingleThreadExecutor()

        val otherThreadSaw = try {
            executor.submit<Boolean> { StreamingState.alreadyAnswered() }.get(5, TimeUnit.SECONDS)
        } finally {
            executor.shutdownNow()
        }

        assertTrue(StreamingState.alreadyAnswered(), "this thread's flag must be unaffected")
        assertFalse(otherThreadSaw, "another invocation must not observe this one's flag")
    }
}
