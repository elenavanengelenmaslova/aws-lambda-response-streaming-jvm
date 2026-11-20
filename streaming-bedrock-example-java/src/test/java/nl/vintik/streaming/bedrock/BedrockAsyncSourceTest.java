package nl.vintik.streaming.bedrock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.reactivestreams.Subscription;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.core.async.SdkPublisher;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeAsyncClient;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelWithResponseStreamRequest;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelWithResponseStreamResponseHandler;
import software.amazon.awssdk.services.bedrockruntime.model.ResponseStream;

/**
 * Tests the SDK glue of {@link BedrockAsyncSource}: the handler it passes to a mocked client is
 * driven with a minimal {@link SdkPublisher} test double of chunk events, then completed or failed.
 */
class BedrockAsyncSourceTest {
    private static final String MODEL_ID = "test-model";
    private static final PromptRequest REQUEST = new PromptRequest("Say hi", 50);

    private final BedrockRuntimeAsyncClient client = mock(BedrockRuntimeAsyncClient.class);
    private final List<String> received = new ArrayList<>();
    private final DeltaSink sink = received::add;

    private BedrockAsyncSource source() {
        return new BedrockAsyncSource(() -> client, () -> MODEL_ID);
    }

    /**
     * Must use {@code ResponseStream.chunkBuilder()}: a plain {@code PayloadPart.builder()} part throws
     * {@code UnsupportedOperationException} from {@code accept(visitor)}, so the visitor never sees it.
     */
    private static ResponseStream chunk(String novaEventJson) {
        return ResponseStream.chunkBuilder().bytes(SdkBytes.fromUtf8String(novaEventJson)).build();
    }

    private static String delta(String text) {
        return "{\"contentBlockDelta\":{\"delta\":{\"text\":\"" + text + "\"},\"contentBlockIndex\":0}}";
    }

    private interface Driver {
        void drive(InvokeModelWithResponseStreamResponseHandler handler);
    }

    /** Makes the mocked client run {@code driver} against the captured handler, then return {@code result}. */
    private void stubClient(Driver driver, CompletableFuture<Void> result) {
        when(client.invokeModelWithResponseStream(
                any(InvokeModelWithResponseStreamRequest.class),
                any(InvokeModelWithResponseStreamResponseHandler.class)))
                .thenAnswer(invocation -> {
                    driver.drive(invocation.getArgument(1));
                    return result;
                });
    }

    /** Emits every part synchronously on subscribe, so completion cannot overtake the chunks. */
    private static SdkPublisher<ResponseStream> syncPublisher(List<ResponseStream> parts) {
        return subscriber -> subscriber.onSubscribe(new Subscription() {
            private boolean emitted;

            @Override
            public void request(long n) {
                if (!emitted) {
                    emitted = true;
                    parts.forEach(subscriber::onNext);
                    subscriber.onComplete();
                }
            }

            @Override
            public void cancel() {
                emitted = true;
            }
        });
    }

    private static void emit(InvokeModelWithResponseStreamResponseHandler handler, String... texts) {
        List<ResponseStream> parts = new ArrayList<>();
        for (String text : texts) {
            parts.add(chunk(delta(text)));
        }
        handler.onEventStream(syncPublisher(parts));
    }

    @Test
    void chunksAreRelayedInOrderThenCompletes() throws Exception {
        stubClient(handler -> {
            emit(handler, "Hel", "lo");
            handler.complete();
        }, CompletableFuture.completedFuture(null));

        source().stream(REQUEST, Deadline.after(Duration.ofSeconds(5)), sink);

        assertEquals(List.of("Hel", "lo"), received);
    }

    @Test
    void requestCarriesModelIdAndNovaBody() throws Exception {
        stubClient(InvokeModelWithResponseStreamResponseHandler::complete, CompletableFuture.completedFuture(null));

        source().stream(REQUEST, Deadline.after(Duration.ofSeconds(5)), sink);

        ArgumentCaptor<InvokeModelWithResponseStreamRequest> captor =
                ArgumentCaptor.forClass(InvokeModelWithResponseStreamRequest.class);
        verify(client).invokeModelWithResponseStream(captor.capture(), any(InvokeModelWithResponseStreamResponseHandler.class));
        assertEquals(MODEL_ID, captor.getValue().modelId());
        assertEquals(NovaPayload.requestBody(REQUEST.prompt(), REQUEST.maxTokens()),
                captor.getValue().body().asUtf8String());
    }

    @Test
    void nonTextAndMalformedChunksAreSkippedWithoutFailing() throws Exception {
        stubClient(handler -> {
            handler.onEventStream(syncPublisher(List.of(
                    chunk("{\"messageStart\":{\"role\":\"assistant\"}}"),
                    chunk("{not json"),
                    chunk(delta("ok")))));
            handler.complete();
        }, CompletableFuture.completedFuture(null));

        source().stream(REQUEST, Deadline.after(Duration.ofSeconds(5)), sink);

        assertEquals(List.of("ok"), received);
    }

    @Test
    void errorBeforeAnyChunkFailsWithoutDeltas() {
        RuntimeException boom = new IllegalStateException("boom");
        stubClient(handler -> handler.exceptionOccurred(boom), CompletableFuture.failedFuture(boom));

        ModelStreamException e = assertThrows(ModelStreamException.class,
                () -> source().stream(REQUEST, Deadline.after(Duration.ofSeconds(5)), sink));

        assertSame(boom, e.getCause());
        assertTrue(received.isEmpty());
    }

    @Test
    void errorAfterSomeChunksDeliversThemThenFails() {
        RuntimeException boom = new IllegalStateException("boom");
        stubClient(handler -> {
            emit(handler, "a", "b");
            handler.exceptionOccurred(boom);
        }, CompletableFuture.failedFuture(boom));

        ModelStreamException e = assertThrows(ModelStreamException.class,
                () -> source().stream(REQUEST, Deadline.after(Duration.ofSeconds(5)), sink));

        assertSame(boom, e.getCause());
        assertEquals(List.of("a", "b"), received);
    }

    @Test
    void failedCallFutureAloneSurfacesAsModelStreamException() {
        RuntimeException boom = new IllegalStateException("transport");
        stubClient(handler -> { }, CompletableFuture.failedFuture(boom));

        ModelStreamException e = assertThrows(ModelStreamException.class,
                () -> source().stream(REQUEST, Deadline.after(Duration.ofSeconds(5)), sink));

        assertInstanceOf(IllegalStateException.class, e.getCause());
    }

    @Test
    void stalledStreamTimesOutAndCancelsTheCall() {
        CompletableFuture<Void> pending = new CompletableFuture<>();
        stubClient(handler -> { }, pending);

        assertThrows(ModelStreamTimeoutException.class,
                () -> source().stream(REQUEST, Deadline.after(Duration.ofMillis(100)), sink));

        assertTrue(pending.isCancelled());
    }

    @Test
    void clientIsBuiltOncePerSourceAndOnlyOnFirstUse() throws Exception {
        int[] builds = {0};
        BedrockAsyncSource source = new BedrockAsyncSource(() -> {
            builds[0]++;
            return client;
        }, () -> MODEL_ID);
        assertEquals(0, builds[0]);
        stubClient(InvokeModelWithResponseStreamResponseHandler::complete, CompletableFuture.completedFuture(null));

        source.stream(REQUEST, Deadline.after(Duration.ofSeconds(5)), sink);
        source.stream(REQUEST, Deadline.after(Duration.ofSeconds(5)), sink);

        assertEquals(1, builds[0]);
        verify(client, times(2)).invokeModelWithResponseStream(
                any(InvokeModelWithResponseStreamRequest.class),
                any(InvokeModelWithResponseStreamResponseHandler.class));
    }
}
