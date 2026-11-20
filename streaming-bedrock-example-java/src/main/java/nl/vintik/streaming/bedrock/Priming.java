package nl.vintik.streaming.bedrock;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.function.Supplier;
import nl.vintik.lambda.streaming.ResponseMetadata;
import nl.vintik.lambda.streaming.ResponseWriter;
import nl.vintik.lambda.streaming.sse.SseWriter;
import org.crac.Context;
import org.crac.Core;
import org.crac.Resource;
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeAsyncClient;

/**
 * CRaC warm-up for SnapStart. Registers with the global context on construction, so the owning
 * handler must hold an instance in a static field.
 *
 * <p>{@link #beforeCheckpoint} exercises each critical path exactly once: build and close an async
 * Bedrock client (loads Netty), parse a primed event, encode one SSE event and serialise response
 * metadata. It never calls the model and never touches the Runtime API transport. Errors
 * propagate so a broken snapshot fails at creation rather than at first request. The throwaway
 * client is closed, so no credentials resolved at snapshot time survive restore.
 */
final class Priming implements Resource {
    private static final String PRIMED_EVENT = "{\"prompt\":\"warm-up\",\"maxTokens\":1}";

    private final Supplier<? extends AutoCloseable> clientFactory;
    private final Supplier<?> parser;
    private final Supplier<?> sseEncoder;
    private final Supplier<?> metadataWriter;

    /** Production wiring. */
    Priming() {
        this(Priming::buildClient, Priming::parsePrimedEvent, Priming::encodeSseEvent, Priming::writeMetadata);
    }

    /** Test seam: each supplier performs one critical path when invoked. */
    Priming(
            Supplier<? extends AutoCloseable> clientFactory,
            Supplier<?> parser,
            Supplier<?> sseEncoder,
            Supplier<?> metadataWriter) {
        this.clientFactory = clientFactory;
        this.parser = parser;
        this.sseEncoder = sseEncoder;
        this.metadataWriter = metadataWriter;
        Core.getGlobalContext().register(this);
    }

    @Override
    public void beforeCheckpoint(Context<? extends Resource> context) throws Exception {
        // Built and closed only to load and warm the client stack.
        try (AutoCloseable ignored = clientFactory.get()) {
            // intentionally empty
        }
        parser.get();
        sseEncoder.get();
        metadataWriter.get();
    }

    @Override
    public void afterRestore(Context<? extends Resource> context) {
        // Nothing to restore: the production client is created lazily on first invocation.
    }

    private static AutoCloseable buildClient() {
        return BedrockRuntimeAsyncClient.builder()
                .httpClientBuilder(NettyNioAsyncHttpClient.builder())
                .build();
    }

    private static PromptResult parsePrimedEvent() {
        return new PromptParser().resolve(
                new ByteArrayInputStream(PRIMED_EVENT.getBytes(StandardCharsets.UTF_8)));
    }

    private static Integer encodeSseEvent() {
        return new SseWriter().writeEvent(new ByteArrayOutputStream(), "warm-up");
    }

    private static Integer writeMetadata() {
        var out = new ByteArrayOutputStream();
        new ResponseWriter().writeMetadata(
                out, new ResponseMetadata(200, Map.of("Content-Type", "text/event-stream")));
        return out.size();
    }
}
