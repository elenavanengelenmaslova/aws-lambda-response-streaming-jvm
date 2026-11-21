package nl.vintik.streaming.bedrock;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeAsyncClient;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelWithResponseStreamRequest;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelWithResponseStreamResponseHandler;

/**
 * Streams Amazon Nova output through the asynchronous Bedrock Runtime client.
 *
 * <p>SDK callbacks run on Netty threads and only enqueue into a {@link DeltaBridge}; every write to
 * the live stream happens on the invocation thread inside {@link DeltaBridge#drain}.
 */
final class BedrockAsyncSource implements TokenStream {
    private static final Logger LOGGER = LoggerFactory.getLogger(BedrockAsyncSource.class);
    static final String DEFAULT_MODEL_ID = "eu.amazon.nova-lite-v1:0";
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(60);

    private final Supplier<BedrockRuntimeAsyncClient> clientFactory;
    private final Supplier<String> modelIdSupplier;
    private volatile BedrockRuntimeAsyncClient client;

    BedrockAsyncSource() {
        this(BedrockAsyncSource::buildClient, BedrockAsyncSource::modelIdFromEnvironment);
    }

    /** For tests: supplies the client and the model id. */
    BedrockAsyncSource(Supplier<BedrockRuntimeAsyncClient> clientFactory, Supplier<String> modelIdSupplier) {
        this.clientFactory = clientFactory;
        this.modelIdSupplier = modelIdSupplier;
    }

    @Override
    public void stream(PromptRequest request, Deadline deadline, DeltaSink sink) throws IOException {
        DeltaBridge bridge = new DeltaBridge();
        InvokeModelWithResponseStreamRequest sdkRequest = InvokeModelWithResponseStreamRequest.builder()
                .modelId(modelIdSupplier.get())
                .contentType("application/json")
                .accept("application/json")
                .body(SdkBytes.fromUtf8String(NovaPayload.requestBody(request.prompt(), request.maxTokens())))
                .build();

        var visitor = InvokeModelWithResponseStreamResponseHandler.Visitor.builder()
                .onChunk(part -> {
                    try {
                        bridge.offerDelta(NovaPayload.textDelta(part.bytes().asUtf8String()));
                    } catch (RuntimeException e) {
                        // Never write to the live stream here, and never log the payload.
                        LOGGER.warn("Skipping a stream chunk ({})", e.getClass().getSimpleName());
                    }
                })
                .build();
        var handler = InvokeModelWithResponseStreamResponseHandler.builder()
                .subscriber(visitor)
                .onComplete(bridge::offerDone)
                .onError(bridge::offerFailure)
                .build();

        CompletableFuture<Void> call = client().invokeModelWithResponseStream(sdkRequest, handler);
        call.whenComplete((ignored, failure) -> {
            if (failure != null) {
                bridge.offerFailure(failure);
            }
        });
        bridge.drain(deadline, sink, () -> call.cancel(true));
    }

    /** Built on first use, after any SnapStart restore. */
    private BedrockRuntimeAsyncClient client() {
        BedrockRuntimeAsyncClient local = client;
        if (local == null) {
            synchronized (this) {
                local = client;
                if (local == null) {
                    local = clientFactory.get();
                    client = local;
                }
            }
        }
        return local;
    }

    private static BedrockRuntimeAsyncClient buildClient() {
        return BedrockRuntimeAsyncClient.builder()
                .httpClientBuilder(NettyNioAsyncHttpClient.builder().readTimeout(READ_TIMEOUT))
                .build();
    }

    private static String modelIdFromEnvironment() {
        String configured = System.getenv("MODEL_ID");
        return configured == null || configured.isBlank() ? DEFAULT_MODEL_ID : configured;
    }
}
