package nl.vintik.lambda.bedrock

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class StreamTimingsTest {

    @ParameterizedTest(name = "[{index}] events={0}")
    @ValueSource(ints = [1, 3, 4, 99, 100, 101, 250])
    fun `Given any number of events When writes are timed Then each write's byte count is returned unchanged`(events: Int) {
        val timings = StreamTimings("req-1")
        var sum = 0
        for (n in 1..events) {
            timings.onDelta()
            sum += timings.timeEventWrite(n) { n * 10 }
        }
        timings.streamEnd(events)
        assertEquals((1..events).sumOf { it * 10 }, sum)
    }

    @Test
    fun `Given a timed block When it completes Then its result is returned`() {
        val timings = StreamTimings("req-2")
        assertEquals("done", timings.timed("stage") { "done" })
        timings.mark("closed")
    }
}
