package nl.vintik.streaming.bedrock;

import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Per-request timing log lines for locating where streamed bytes are held: when Bedrock delivers
 * its first delta, when the stream ends and when the handler closes.
 *
 * <p>Every line is {@code timing requestId=… stage=… tMs=… epochMs=…}. The request id is the same
 * value API Gateway logs as {@code $context.integration.requestId} in the stage access log, so the
 * handler lines can be joined to the server-side timings (Requirement 8.5). The prompt and the
 * model id are never logged.
 */
final class StreamTimings {

    private static final Logger LOGGER = LoggerFactory.getLogger(StreamTimings.class);
    private static final long NANOS_PER_MILLI = 1_000_000L;

    private final String requestId;
    private final long startNanos = System.nanoTime();
    private final AtomicBoolean firstDeltaSeen = new AtomicBoolean();
    private volatile long lastDeltaMs = -1L;

    StreamTimings(String requestId) {
        this.requestId = requestId == null ? "" : requestId;
    }

    /** Logs {@code stage} with the time since the handler started. */
    void mark(String stage) {
        mark(stage, "");
    }

    /** Logs {@code stage}, appending {@code extra} (not the prompt or model id) when non-empty. */
    void mark(String stage, String extra) {
        String suffix = extra == null || extra.isEmpty() ? "" : " " + extra;
        LOGGER.info("timing requestId={} stage={} tMs={} epochMs={}{}",
                requestId, stage, sinceStartMs(), System.currentTimeMillis(), suffix);
    }

    /** Records that a model delta reached the handler; logs the first one as {@code firstDelta}. */
    void onDelta() {
        lastDeltaMs = sinceStartMs();
        if (firstDeltaSeen.compareAndSet(false, true)) {
            mark("firstDelta");
        }
    }

    /** Logs {@code streamEnd} with the number of events written and the time of the last delta. */
    void streamEnd(int events) {
        mark("streamEnd", "events=" + events + " lastDeltaMs=" + lastDeltaMs);
    }

    private long sinceStartMs() {
        return (System.nanoTime() - startNanos) / NANOS_PER_MILLI;
    }
}
