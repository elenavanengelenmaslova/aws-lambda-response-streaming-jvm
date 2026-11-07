package nl.vintik.lambda.streaming.sse

import nl.vintik.lambda.streaming.sse.PacedSseEmitter.Companion.MAX_DELAY_MILLIS
import nl.vintik.lambda.streaming.sse.PacedSseEmitter.Companion.MIN_DELAY_MILLIS
import nl.vintik.lambda.streaming.sse.PacedSseEmitter.Companion.NO_DELAY
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.OutputStream

/**
 * Example/edge-case unit tests for [PacedSseEmitter] — construction-time validation and the
 * sleep-boundary semantics (`end` and [PacedSseEmitter.NO_DELAY] never sleep). The universal
 * pacing/no-pacing properties live in the sibling property-test files; this file pins the concrete
 * rejection cases and the boundary guarantees via a recording sleeper.
 */
class PacedSseEmitterTest {

    /** Captures every requested sleep duration (ms) so tests can assert the sleep schedule with zero wall-clock delay. */
    private class RecordingSleeper : (Long) -> Unit {
        val sleeps = mutableListOf<Long>()
        override fun invoke(millis: Long) {
            sleeps.add(millis)
        }
    }

    /** A throwaway sink; pacing is observed through the sleeper, not the bytes. */
    private fun sink(): OutputStream = ByteArrayOutputStream()

    /** A writer that carries padding, so delay configuration is valid. */
    private fun paddedWriter(): PaddedSseWriter =
        PaddedSseWriter(headPadBytes = 100, tailPadBytes = 10, headPadEvents = 1)

    // --- Construction-time validation (Requirements 3.3, 3.4, 3.6) ---

    @Test
    fun `Given a delay above the maximum When constructed Then it is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            PacedSseEmitter(paddedWriter(), delayMillis = MAX_DELAY_MILLIS + 1)
        }
    }

    @Test
    fun `Given a negative delay that is not the no-delay sentinel When constructed Then it is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            PacedSseEmitter(paddedWriter(), delayMillis = -1)
        }
    }

    @Test
    fun `Given a delay below the minimum that is not the no-delay sentinel When constructed Then it is rejected`() {
        // MIN_DELAY_MILLIS - 1 is 0, which is NO_DELAY and thus allowed; a value strictly between
        // NO_DELAY and MIN that is still invalid can only be negative, so use -5 to exercise the
        // "below minimum, not sentinel" branch distinctly from -1 above.
        assertThrows(IllegalArgumentException::class.java) {
            PacedSseEmitter(paddedWriter(), delayMillis = -5)
        }
    }

    @Test
    fun `Given a configured delay with a zero-padding writer When constructed Then it is rejected`() {
        val noPaddingWriter = PaddedSseWriter(headPadBytes = 0, tailPadBytes = 0, headPadEvents = 0)
        assertTrue(!noPaddingWriter.hasPadding(), "writer must carry no padding for this case")
        assertThrows(IllegalArgumentException::class.java) {
            PacedSseEmitter(noPaddingWriter, delayMillis = 100)
        }
    }

    @Test
    fun `Given the minimum delay with a padded writer When constructed Then it succeeds`() {
        // Boundary: MIN_DELAY_MILLIS and MAX_DELAY_MILLIS are valid with padding present.
        PacedSseEmitter(paddedWriter(), delayMillis = MIN_DELAY_MILLIS, sleep = RecordingSleeper())
        PacedSseEmitter(paddedWriter(), delayMillis = MAX_DELAY_MILLIS, sleep = RecordingSleeper())
    }

    // --- Sleep-boundary semantics (Requirements 3.2, with a recording sleeper) ---

    @Test
    fun `Given a NO_DELAY emitter When multiple events are emitted Then the sleeper is never invoked`() {
        val sleeper = RecordingSleeper()
        val emitter = PacedSseEmitter(paddedWriter(), delayMillis = NO_DELAY, sleep = sleeper)

        repeat(5) { emitter.emit(sink(), "event-$it") }

        assertEquals(0, sleeper.sleeps.size, "NO_DELAY must never pause between events")
    }

    @Test
    fun `Given a configured emitter When end is called Then it never adds a trailing sleep`() {
        val sleeper = RecordingSleeper()
        val emitter = PacedSseEmitter(paddedWriter(), delayMillis = 100, sleep = sleeper)

        // Two events produce exactly one inter-event gap; end must add nothing on top.
        emitter.emit(sink(), "a")
        emitter.emit(sink(), "b")
        val sleepsBeforeEnd = sleeper.sleeps.size
        emitter.end(sink())

        assertEquals(1, sleepsBeforeEnd, "two events yield exactly one inter-event sleep")
        assertEquals(sleepsBeforeEnd, sleeper.sleeps.size, "end must never add a trailing sleep")
    }

    @Test
    fun `Given a configured emitter When only end is called Then the sleeper is never invoked`() {
        val sleeper = RecordingSleeper()
        val emitter = PacedSseEmitter(paddedWriter(), delayMillis = 250, sleep = sleeper)

        emitter.end(sink())

        assertEquals(0, sleeper.sleeps.size, "end alone must never sleep")
    }
}
