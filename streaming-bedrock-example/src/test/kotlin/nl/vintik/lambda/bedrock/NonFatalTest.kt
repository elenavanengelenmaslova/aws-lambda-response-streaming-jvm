package nl.vintik.lambda.bedrock

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

class NonFatalTest {

    @Test
    fun `Given a successful block When run Then the value is returned`() {
        val result = runCatchingNonFatal { 42 }
        assertEquals(42, result.getOrNull())
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("recoverable")
    fun `Given a recoverable exception When run Then it is captured as a failure`(e: Throwable) {
        val result = runCatchingNonFatal { throw e }
        val captured = result.exceptionOrNull()
        assertNotNull(captured)
        assertSame(e, captured)
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("fatal")
    fun `Given a fatal throwable When run Then it propagates instead of being captured`(e: Throwable) {
        val thrown = assertThrows(Throwable::class.java) { runCatchingNonFatal { throw e } }
        assertSame(e, thrown)
    }

    companion object {
        @JvmStatic
        fun recoverable(): List<Throwable> = listOf(
            IllegalStateException("bedrock unavailable"),
            IllegalArgumentException("bad input"),
            IOException("stream closed"),
            RuntimeException("generic"),
            NumberFormatException("not a number"),
        )

        @JvmStatic
        fun fatal(): List<Throwable> = listOf(
            CancellationException("cancelled"),
            InterruptedException("interrupted"),
            OutOfMemoryError("oom"),
            StackOverflowError("sof"),
            AssertionError("assert"),
        )
    }
}
