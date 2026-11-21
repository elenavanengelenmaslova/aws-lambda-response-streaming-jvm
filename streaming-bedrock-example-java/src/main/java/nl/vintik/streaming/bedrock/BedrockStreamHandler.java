package nl.vintik.streaming.bedrock;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestStreamHandler;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Map;
import nl.vintik.lambda.streaming.ResponseMetadata;
import nl.vintik.lambda.streaming.ResponseWriter;
import nl.vintik.lambda.streaming.sse.SseWriter;
import nl.vintik.lambda.streaming.sse.StreamingResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Relays Amazon Bedrock Nova tokens to the client as Server-Sent Events.
 *
 * <p>A rejected request is answered on the runtime-provided buffered stream, before anything is
 * committed. A resolved request opens the live streaming response; from then on the status is
 * already sent, so a failure can only be signalled with an SSE {@code error} event followed by a
 * failed invocation, never by a different status code.
 */
public final class BedrockStreamHandler implements RequestStreamHandler {

    // Held in a static field so class initialization registers the CRaC hook (Req 7.2).
    @SuppressWarnings("unused")
    private static final Priming PRIMING = new Priming();

    private static final Logger LOGGER = LoggerFactory.getLogger(BedrockStreamHandler.class);

    private static final String ERROR_EVENT = "error";
    private static final String ERROR_MESSAGE = "The response stream failed.";

    private static final ResponseMetadata SSE_METADATA = new ResponseMetadata(
            200,
            Map.of(
                    "Content-Type", "text/event-stream; charset=utf-8",
                    "Cache-Control", "no-cache",
                    "Transfer-Encoding", "chunked"));

    private final PromptParser parser;
    private final TokenStream tokens;
    private final ResponseWriter writer;
    private final StreamingResponse streaming;

    public BedrockStreamHandler() {
        this(new PromptParser(), new BedrockAsyncSource(), new ResponseWriter(), new StreamingResponse());
    }

    BedrockStreamHandler(
            PromptParser parser, TokenStream tokens, ResponseWriter writer, StreamingResponse streaming) {
        this.parser = parser;
        this.tokens = tokens;
        this.writer = writer;
        this.streaming = streaming;
    }

    @Override
    public void handleRequest(InputStream input, OutputStream output, Context context) throws IOException {
        StreamTimings timings = new StreamTimings(context.getAwsRequestId());
        timings.mark("start");

        PromptResult result = parser.resolve(input);
        if (result instanceof PromptResult.Rejected rejected) {
            // Nothing is committed yet: answer on the runtime's own buffered stream.
            writer.writeError(output, rejected.status(), rejected.message());
            return;
        }
        PromptRequest request = ((PromptResult.Resolved) result).request();
        Deadline deadline = Deadline.fromContext(context);

        streaming.stream(
                context.getAwsRequestId(), SSE_METADATA, live -> relay(request, deadline, live, timings));
        timings.mark("closed");
    }

    private void relay(PromptRequest request, Deadline deadline, OutputStream live, StreamTimings timings)
            throws IOException {
        SseWriter sse = new SseWriter();
        LineEndings lineEndings = new LineEndings();
        try {
            tokens.stream(request, deadline, delta -> {
                sse.writeEvent(live, lineEndings.normalize(delta));
                timings.onDelta();
            });
            sse.writeEnd(live);
            timings.streamEnd(sse.eventsWritten());
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Streaming failed: {}", e.getClass().getSimpleName(), e);
            writeErrorEvent(sse, live);
            throw e;
        }
    }

    /**
     * SSE treats a lone CR and CRLF as line terminators as well as LF, but {@link SseWriter} splits
     * on LF only. A CR inside a model delta would therefore end the {@code data:} line early and
     * let the rest be read as another field. Contract: line endings in model text are normalised
     * to LF, which a client reassembles as a newline.
     */
    static String normalizeLineEndings(String text) {
        return text.indexOf('\r') < 0 ? text : text.replace("\r\n", "\n").replace('\r', '\n');
    }

    /**
     * Per-stream line-ending normaliser. A CRLF can be split across two deltas ("left\r" then
     * "\nright"); normalising each delta alone would turn that into two newlines, so this remembers
     * whether the last model character was a CR and drops the LF that completes it. Empty deltas
     * leave the state alone. One instance per relay, never shared.
     */
    static final class LineEndings {
        private boolean afterCr;

        String normalize(String delta) {
            if (delta.isEmpty()) {
                return delta;
            }
            String text = afterCr && delta.charAt(0) == '\n' ? delta.substring(1) : delta;
            afterCr = delta.charAt(delta.length() - 1) == '\r';
            return normalizeLineEndings(text);
        }
    }

    private static void writeErrorEvent(SseWriter sse, OutputStream live) {
        try {
            sse.writeNamedEvent(live, ERROR_EVENT, ERROR_MESSAGE);
        } catch (Exception e) {
            // SseWriter declares no checked exceptions, yet Kotlin lets an IOException through
            // undeclared, so catch broadly: this write is best effort only.
            LOGGER.warn("Could not write the error event: {}", e.getClass().getSimpleName());
        }
    }
}
