package nl.vintik.streaming.bedrock;

import java.io.IOException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Hands model signals from SDK callback threads to the invocation thread.
 *
 * <p>Producers only enqueue: the {@code offer*} methods never block and never throw. The
 * invocation thread calls {@link #drain} and performs every write to the live stream itself.
 * The first terminal signal ({@code Done} or {@code Failed}) wins; later ones are ignored.
 */
final class DeltaBridge {

    sealed interface Signal permits Delta, Done, Failed {}

    record Delta(String text) implements Signal {}

    record Done() implements Signal {}

    record Failed(Throwable cause) implements Signal {}

    private final LinkedBlockingQueue<Signal> queue = new LinkedBlockingQueue<>();
    private final AtomicBoolean terminalOffered = new AtomicBoolean();

    /** Enqueues a text delta; a {@code null} or empty text is ignored. Never blocks or throws. */
    void offerDelta(String text) {
        if (text == null || text.isEmpty() || terminalOffered.get()) {
            return;
        }
        queue.offer(new Delta(text));
    }

    /** Signals normal completion. Ignored if a terminal signal was already offered. */
    void offerDone() {
        if (terminalOffered.compareAndSet(false, true)) {
            queue.offer(new Done());
        }
    }

    /** Signals failure. Ignored if a terminal signal was already offered. */
    void offerFailure(Throwable cause) {
        if (terminalOffered.compareAndSet(false, true)) {
            queue.offer(new Failed(cause));
        }
    }

    /**
     * Relays deltas to {@code sink} until a terminal signal arrives.
     *
     * @param cancelAction run once if the deadline passes or the thread is interrupted
     * @throws ModelStreamException on a model failure or interrupt
     * @throws ModelStreamTimeoutException when no terminal signal arrives before the deadline
     * @throws IOException when the sink fails to write
     */
    void drain(Deadline deadline, DeltaSink sink, Runnable cancelAction) throws IOException {
        while (true) {
            Signal signal;
            try {
                signal = queue.poll(Math.max(0, deadline.remainingNanos()), TimeUnit.NANOSECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                cancelAction.run();
                throw new ModelStreamException("Interrupted while waiting for the model stream", e);
            }
            if (signal == null) {
                cancelAction.run();
                throw new ModelStreamTimeoutException("The model stream did not finish before the deadline");
            }
            switch (signal) {
                case Delta delta -> sink.accept(delta.text());
                case Done done -> {
                    return;
                }
                case Failed failed ->
                    throw new ModelStreamException("The model stream failed", failed.cause());
            }
        }
    }
}
