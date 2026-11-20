package nl.vintik.streaming.bedrock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Feature: java-bedrock-sse-streaming-endpoint, Property 5: the bridge preserves order and terminal semantics.
 *
 * <p>Producers run on real threads with seeded, sub-millisecond-to-few-millisecond delays between
 * signals. Assertions are about ordering and outcome, never about how fast a producer ran. Only the
 * no-terminal scenario measures time, against a generous upper tolerance.
 *
 * <p><b>Validates: Requirements 4.2, 4.3, 4.5, 4.6, 4.10</b>
 */
class DeltaBridgePropertyTest {

    /** Ample deadline for scenarios that must terminate on their own. */
    private static final Duration GENEROUS = Duration.ofSeconds(30);
    /** Upper bound on any single scenario, to turn a hang into a failure. */
    private static final Duration HARD_LIMIT = Duration.ofSeconds(20);
    private static final Duration SHORT_DEADLINE = Duration.ofMillis(200);
    private static final Duration TIMEOUT_TOLERANCE = Duration.ofSeconds(5);

    private enum Terminal { DONE, FAILURE, NONE, DONE_THEN_FAILURE, FAILURE_THEN_DONE }

    static Stream<Arguments> scenarios() {
        return Stream.of(
                Arguments.of("empty complete sequence", 0, Terminal.DONE, 1L),
                Arguments.of("single delta complete", 1, Terminal.DONE, 2L),
                Arguments.of("5 deltas complete", 5, Terminal.DONE, 3L),
                Arguments.of("30 deltas complete", 30, Terminal.DONE, 4L),
                Arguments.of("150 deltas complete", 150, Terminal.DONE, 5L),
                Arguments.of("failure before any delta", 0, Terminal.FAILURE, 6L),
                Arguments.of("failure after 1 delta", 1, Terminal.FAILURE, 7L),
                Arguments.of("failure after 7 deltas", 7, Terminal.FAILURE, 8L),
                Arguments.of("failure after 40 deltas", 40, Terminal.FAILURE, 9L),
                Arguments.of("no terminal, no deltas", 0, Terminal.NONE, 10L),
                Arguments.of("no terminal after 3 deltas", 3, Terminal.NONE, 11L),
                Arguments.of("done then failure, first wins", 4, Terminal.DONE_THEN_FAILURE, 12L),
                Arguments.of("done then failure, no deltas", 0, Terminal.DONE_THEN_FAILURE, 13L),
                Arguments.of("failure then done, first wins", 4, Terminal.FAILURE_THEN_DONE, 14L),
                Arguments.of("failure then done, no deltas", 0, Terminal.FAILURE_THEN_DONE, 15L),
                Arguments.of("60 deltas complete, other seed", 60, Terminal.DONE, 16L));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("scenarios")
    @DisplayName("Feature: java-bedrock-sse-streaming-endpoint, Property 5: the bridge preserves order and terminal semantics")
    void bridgePreservesOrderAndTerminalSemantics(String name, int deltaCount, Terminal terminal, long seed)
            throws Exception {
        List<String> expected = new ArrayList<>();
        for (int i = 0; i < deltaCount; i++) {
            expected.add("delta-" + i);
        }
        DeltaBridge bridge = new DeltaBridge();
        RuntimeException failure = new RuntimeException("boom");
        AtomicInteger cancels = new AtomicInteger();
        List<String> received = new ArrayList<>();
        Deadline deadline = Deadline.after(terminal == Terminal.NONE ? SHORT_DEADLINE : GENEROUS);

        Thread producer = new Thread(() -> produce(bridge, expected, terminal, failure, seed), "test-producer");
        long start = System.nanoTime();
        producer.start();
        Throwable thrown = assertTimeoutPreemptively(HARD_LIMIT, () -> {
            try {
                bridge.drain(deadline, received::add, cancels::incrementAndGet);
                return null;
            } catch (RuntimeException e) {
                return e;
            }
        });
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);
        producer.join(HARD_LIMIT.toMillis());

        // Every delta is delivered, in order, exactly once, whatever the terminal outcome.
        assertEquals(expected, received);

        switch (terminal) {
            case DONE, DONE_THEN_FAILURE -> {
                assertEquals(null, thrown);
                assertEquals(0, cancels.get());
            }
            case FAILURE, FAILURE_THEN_DONE -> {
                assertNotNull(thrown);
                assertEquals(ModelStreamException.class, thrown.getClass());
                assertSame(failure, thrown.getCause());
                assertEquals(0, cancels.get());
            }
            case NONE -> {
                assertNotNull(thrown);
                assertInstanceOf(ModelStreamTimeoutException.class, thrown);
                assertEquals(1, cancels.get());
                assertTrue(elapsed.compareTo(SHORT_DEADLINE.minusMillis(20)) >= 0,
                        "returned before the deadline: " + elapsed);
                assertTrue(elapsed.compareTo(SHORT_DEADLINE.plus(TIMEOUT_TOLERANCE)) <= 0,
                        "timed out too late: " + elapsed);
            }
        }
    }

    @Test
    @DisplayName("Feature: java-bedrock-sse-streaming-endpoint, Property 5: an interrupted consumer cancels and fails, keeping the flag")
    void interruptedConsumerCancelsOnceAndRestoresInterruptFlag() throws Exception {
        DeltaBridge bridge = new DeltaBridge();
        AtomicInteger cancels = new AtomicInteger();
        // Run on a dedicated thread so the interrupt flag cannot leak into the test runner.
        Throwable[] thrown = new Throwable[1];
        boolean[] flagAfter = new boolean[1];
        Thread consumer = new Thread(() -> {
            Thread.currentThread().interrupt();
            try {
                bridge.drain(Deadline.after(GENEROUS), text -> { }, cancels::incrementAndGet);
            } catch (Throwable t) {
                thrown[0] = t;
            }
            flagAfter[0] = Thread.currentThread().isInterrupted();
        }, "test-consumer");
        consumer.start();
        consumer.join(HARD_LIMIT.toMillis());

        assertFalse(consumer.isAlive());
        assertNotNull(thrown[0]);
        assertEquals(ModelStreamException.class, thrown[0].getClass());
        assertInstanceOf(InterruptedException.class, thrown[0].getCause());
        assertEquals(1, cancels.get());
        assertTrue(flagAfter[0]);
    }

    @Test
    @DisplayName("Feature: java-bedrock-sse-streaming-endpoint, Property 5: a failing sink surfaces its IOException")
    void sinkFailurePropagates() {
        DeltaBridge bridge = new DeltaBridge();
        bridge.offerDelta("a");
        bridge.offerDone();
        IOException io = new IOException("client went away");

        IOException thrown = assertThrows(IOException.class,
                () -> bridge.drain(Deadline.after(GENEROUS), text -> { throw io; }, () -> { }));

        assertSame(io, thrown);
    }

    @Test
    @DisplayName("Feature: java-bedrock-sse-streaming-endpoint, Property 5: offers return immediately and never throw")
    void offersNeverBlockOrThrow() {
        DeltaBridge bridge = new DeltaBridge();
        int deltas = 200_000;

        assertTimeoutPreemptively(HARD_LIMIT, () -> {
            for (int i = 0; i < deltas; i++) {
                bridge.offerDelta("x");
            }
            bridge.offerDelta(null);
            bridge.offerDelta("");
            bridge.offerFailure(null);
            bridge.offerDone();
            bridge.offerFailure(new RuntimeException("late"));
            bridge.offerDelta("after terminal");
        });

        // Everything offered before the first terminal signal is still delivered, in order.
        AtomicInteger delivered = new AtomicInteger();
        ModelStreamException e = assertThrows(ModelStreamException.class,
                () -> bridge.drain(Deadline.after(GENEROUS), text -> delivered.incrementAndGet(), () -> { }));
        assertEquals(deltas, delivered.get());
        assertEquals(null, e.getCause());
    }

    private static void produce(
            DeltaBridge bridge, List<String> deltas, Terminal terminal, RuntimeException failure, long seed) {
        Random random = new Random(seed);
        try {
            for (String delta : deltas) {
                pause(random);
                bridge.offerDelta(delta);
            }
            pause(random);
            switch (terminal) {
                case DONE -> bridge.offerDone();
                case FAILURE -> bridge.offerFailure(failure);
                case DONE_THEN_FAILURE -> {
                    bridge.offerDone();
                    bridge.offerFailure(failure);
                    bridge.offerDelta("ignored-after-terminal");
                }
                case FAILURE_THEN_DONE -> {
                    bridge.offerFailure(failure);
                    bridge.offerDone();
                    bridge.offerDelta("ignored-after-terminal");
                }
                case NONE -> { }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Random delay of 0 to 3 ms (40% of the time none), so 500-delta scenarios stay quick. */
    private static void pause(Random random) throws InterruptedException {
        if (random.nextInt(10) < 4) {
            return;
        }
        Thread.sleep(1 + random.nextInt(3));
    }
}
