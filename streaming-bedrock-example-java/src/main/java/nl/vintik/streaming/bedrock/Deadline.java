package nl.vintik.streaming.bedrock;

import com.amazonaws.services.lambda.runtime.Context;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * A point in time, measured on the monotonic clock, after which waiting for the model stream
 * must stop. One shared definition of "out of time" for the drain loop and tests.
 */
final class Deadline {

    /** Time kept back from the invocation's remaining time so the handler can fail cleanly. */
    static final Duration SAFETY_MARGIN = Duration.ofSeconds(3);

    private final long deadlineNanos;

    private Deadline(long deadlineNanos) {
        this.deadlineNanos = deadlineNanos;
    }

    /** Deadline at {@code now + remaining invocation time - SAFETY_MARGIN}. */
    static Deadline fromContext(Context context) {
        long remainingMillis = context.getRemainingTimeInMillis();
        long budgetNanos = TimeUnit.MILLISECONDS.toNanos(remainingMillis) - SAFETY_MARGIN.toNanos();
        return new Deadline(System.nanoTime() + budgetNanos);
    }

    /** Deadline {@code duration} from now; intended for tests. */
    static Deadline after(Duration duration) {
        return new Deadline(System.nanoTime() + duration.toNanos());
    }

    /** Nanoseconds left until the deadline; zero or negative once expired. */
    long remainingNanos() {
        return deadlineNanos - System.nanoTime();
    }

    boolean isExpired() {
        return remainingNanos() <= 0;
    }
}
