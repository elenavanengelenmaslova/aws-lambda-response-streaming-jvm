package nl.vintik.streaming.bedrock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.amazonaws.services.lambda.runtime.Context;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import nl.vintik.lambda.streaming.ResponseWriter;
import nl.vintik.lambda.streaming.sse.RuntimeApiChannelFactory;
import nl.vintik.lambda.streaming.sse.StreamingResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * In-process streaming test, no containers: the real handler, the real {@link StreamingResponse}
 * and the real {@link RuntimeApiChannelFactory} talk to a {@code com.sun.net.httpserver} stand-in
 * for the Lambda Runtime API, while a stub {@link TokenStream} sleeps between deltas. The server
 * timestamps each chunk it reads, so the test can prove the first event arrives while the source
 * is still producing, which a buffering handler could not do.
 */
class BedrockStreamHandlerStreamingTest {

    private static final List<String> DELTAS = List.of("alpha", "beta", "gamma", "delta");
    private static final long GAP_MILLIS = 300;
    private static final String REQUEST_ID = "req-streaming-1";
    private static final byte[] DELIMITER = new byte[8];

    private HttpServer server;
    private final AtomicReference<String> requestPath = new AtomicReference<>();
    private final AtomicReference<String> responseMode = new AtomicReference<>();
    private final AtomicReference<String> contentType = new AtomicReference<>();
    private final AtomicReference<String> transferEncoding = new AtomicReference<>();
    private final AtomicReference<byte[]> receivedBody = new AtomicReference<>();
    /** Nanotime at which the cumulative body first contained the first delta's event. */
    private final AtomicLong firstEventArrivalNanos = new AtomicLong();

    @BeforeEach
    void startRuntimeApiStandIn() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            requestPath.set(exchange.getRequestURI().getPath());
            responseMode.set(exchange.getRequestHeaders().getFirst("Lambda-Runtime-Function-Response-Mode"));
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            transferEncoding.set(exchange.getRequestHeaders().getFirst("Transfer-Encoding"));

            ByteArrayOutputStream received = new ByteArrayOutputStream();
            byte[] firstEvent = ("data: " + DELTAS.get(0) + "\n\n").getBytes(StandardCharsets.UTF_8);
            byte[] buffer = new byte[4096];
            try (InputStream body = exchange.getRequestBody()) {
                int read;
                while ((read = body.read(buffer)) != -1) {
                    received.write(buffer, 0, read);
                    if (firstEventArrivalNanos.get() == 0 && indexOf(received.toByteArray(), firstEvent) >= 0) {
                        firstEventArrivalNanos.set(System.nanoTime());
                    }
                }
            }
            receivedBody.set(received.toByteArray());
            exchange.sendResponseHeaders(202, -1);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopRuntimeApiStandIn() {
        server.stop(0);
    }

    @Test
    @DisplayName("`Given a sleeping token source When handled Then the first chunk arrives while the source is still producing`")
    void givenSleepingTokenSourceWhenHandledThenFirstChunkArrivesWhileSourceStillProducing() throws Exception {
        AtomicLong sourceFinishedNanos = new AtomicLong();
        TokenStream sleepingSource = (request, deadline, sink) -> {
            for (String delta : DELTAS) {
                sink.accept(delta);
                sleep(GAP_MILLIS);
            }
            sourceFinishedNanos.set(System.nanoTime());
        };
        BedrockStreamHandler handler = new BedrockStreamHandler(
                new PromptParser(),
                sleepingSource,
                new ResponseWriter(),
                new StreamingResponse(
                        new ResponseWriter(), new RuntimeApiChannelFactory(endpoint()), false));
        Context context = mock(Context.class);
        when(context.getAwsRequestId()).thenReturn(REQUEST_ID);
        when(context.getRemainingTimeInMillis()).thenReturn(60_000);
        ByteArrayOutputStream buffered = new ByteArrayOutputStream();

        handler.handleRequest(new ByteArrayInputStream("{}".getBytes(StandardCharsets.UTF_8)), buffered, context);

        // Streaming headers and target of the post.
        assertEquals("/2018-06-01/runtime/invocation/" + REQUEST_ID + "/response", requestPath.get());
        assertEquals("streaming", responseMode.get());
        assertEquals("application/vnd.awslambda.http-integration-response", contentType.get());
        assertTrue(
                transferEncoding.get() != null && transferEncoding.get().equalsIgnoreCase("chunked"),
                "the response must be posted with chunked transfer encoding");

        // Progressive delivery: the first event reached the server before the source finished.
        assertTrue(firstEventArrivalNanos.get() != 0, "the first event never reached the server");
        assertTrue(sourceFinishedNanos.get() != 0, "the source never finished");
        long headStartMillis = (sourceFinishedNanos.get() - firstEventArrivalNanos.get()) / 1_000_000;
        assertTrue(
                headStartMillis >= GAP_MILLIS * (DELTAS.size() - 1) / 2,
                "first chunk should arrive well before the source finishes, head start was "
                        + headStartMillis + " ms");

        // Wire format: metadata JSON, 8 null bytes, then the SSE body with its terminal event.
        byte[] body = receivedBody.get();
        assertNotNull(body);
        int delimiterAt = indexOf(body, DELIMITER);
        assertTrue(delimiterAt > 0, "metadata and delimiter must precede the body");
        String metadata = new String(body, 0, delimiterAt, StandardCharsets.UTF_8);
        assertTrue(metadata.contains("\"statusCode\":200"), metadata);
        assertTrue(metadata.contains("text/event-stream"), metadata);
        String sse = new String(body, delimiterAt + DELIMITER.length, body.length - delimiterAt - DELIMITER.length,
                StandardCharsets.UTF_8);
        StringBuilder expected = new StringBuilder();
        DELTAS.forEach(d -> expected.append("data: ").append(d).append("\n\n"));
        expected.append("event: end\ndata: [DONE]\n\n");
        assertEquals(expected.toString(), sse);

        // The runtime-provided buffered stream is left untouched on the streaming path.
        assertEquals(0, buffered.size());
    }

    private String endpoint() {
        return InetAddress.getLoopbackAddress().getHostAddress() + ":" + server.getAddress().getPort();
    }

    private static void sleep(long millis) throws IOException {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }
}
