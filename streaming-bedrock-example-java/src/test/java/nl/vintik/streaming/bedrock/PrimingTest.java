package nl.vintik.streaming.bedrock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Feature: java-bedrock-sse-streaming-endpoint, Property 7: priming exercises every path exactly once.
 *
 * <p>Counting suppliers stand in for the four critical paths. The model is never reachable from
 * {@link Priming}: its only collaborators are the four suppliers, and the client the factory
 * returns is only closed.
 *
 * <p><b>Validates: Requirements 7.3, 7.4, 7.8</b>
 */
@Tag("Feature: java-bedrock-sse-streaming-endpoint, Property 7: priming exercises every path exactly once")
class PrimingTest {

    private static final int PATH_COUNT = 4;

    /** Counts invocations of each path and of closing the throwaway client. */
    private static final class Counters {
        final AtomicInteger clientBuilt = new AtomicInteger();
        final AtomicInteger clientClosed = new AtomicInteger();
        final AtomicInteger parsed = new AtomicInteger();
        final AtomicInteger encoded = new AtomicInteger();
        final AtomicInteger metadata = new AtomicInteger();
        /** Index (0..3) of the path that throws, or -1 for none. */
        int failingPath = -1;
        final RuntimeException failure = new IllegalStateException("boom");

        Supplier<AutoCloseable> client() {
            return () -> {
                clientBuilt.incrementAndGet();
                maybeFail(0);
                return clientClosed::incrementAndGet;
            };
        }

        Supplier<Object> parser() {
            return path(parsed, 1);
        }

        Supplier<Object> sse() {
            return path(encoded, 2);
        }

        Supplier<Object> metadata() {
            return path(metadata, 3);
        }

        private Supplier<Object> path(AtomicInteger counter, int index) {
            return () -> {
                counter.incrementAndGet();
                maybeFail(index);
                return "ok";
            };
        }

        private void maybeFail(int index) {
            if (failingPath == index) {
                throw failure;
            }
        }

        Priming priming() {
            return new Priming(client(), parser(), sse(), metadata());
        }

        int total() {
            return clientBuilt.get() + parsed.get() + encoded.get() + metadata.get();
        }
    }

    @ParameterizedTest(name = "{0} checkpoint(s) invoke every path exactly {0} time(s)")
    @ValueSource(ints = {1, 2, 3, 5, 10})
    @DisplayName("`Given N checkpoints When beforeCheckpoint runs Then every path runs exactly once per checkpoint (Property 7)`")
    void eachPathRunsExactlyOncePerCheckpoint(int checkpoints) throws Exception {
        var counters = new Counters();
        var priming = counters.priming();

        for (int i = 1; i <= checkpoints; i++) {
            priming.beforeCheckpoint(null);
            assertEquals(i, counters.clientBuilt.get());
            assertEquals(i, counters.clientClosed.get(), "throwaway client must be closed");
            assertEquals(i, counters.parsed.get());
            assertEquals(i, counters.encoded.get());
            assertEquals(i, counters.metadata.get());
            assertEquals(PATH_COUNT * i, counters.total(), "no other path may be exercised");
        }
    }

    @ParameterizedTest(name = "path {0} throwing propagates")
    @ValueSource(ints = {0, 1, 2, 3})
    @DisplayName("`Given a throwing path When beforeCheckpoint runs Then the exception propagates unchanged`")
    void aThrowingPathPropagatesUnchanged(int failingPath) {
        var counters = new Counters();
        counters.failingPath = failingPath;
        var priming = counters.priming();

        var thrown = assertThrows(RuntimeException.class, () -> priming.beforeCheckpoint(null));

        assertSame(counters.failure, thrown);
        // The failing path ran exactly once; nothing was retried.
        int[] counts = {
            counters.clientBuilt.get(), counters.parsed.get(), counters.encoded.get(), counters.metadata.get()
        };
        for (int i = 0; i < counts.length; i++) {
            if (i < failingPath) {
                assertEquals(1, counts[i], "path before the failure runs once");
            } else if (i == failingPath) {
                assertEquals(1, counts[i], "failing path is not retried");
            } else {
                assertEquals(0, counts[i], "paths after the failure do not run");
            }
        }
    }

    @ParameterizedTest(name = "afterRestore with {0} prior checkpoint(s) runs nothing")
    @ValueSource(ints = {0, 1, 2})
    @DisplayName("`Given prior checkpoints When afterRestore runs Then no path is exercised`")
    void afterRestoreExercisesNoPath(int priorCheckpoints) throws Exception {
        var counters = new Counters();
        var priming = counters.priming();
        for (int i = 0; i < priorCheckpoints; i++) {
            priming.beforeCheckpoint(null);
        }
        int before = counters.total();

        priming.afterRestore(null);

        assertEquals(before, counters.total());
    }
}
