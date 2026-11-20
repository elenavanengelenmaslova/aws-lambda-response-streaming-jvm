package nl.vintik.streaming.bedrock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.amazonaws.services.lambda.runtime.Context;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import nl.vintik.lambda.streaming.ResponseWriter;
import nl.vintik.lambda.streaming.sse.ResponseChannel;
import nl.vintik.lambda.streaming.sse.ResponseChannelFactory;
import nl.vintik.lambda.streaming.sse.StreamingResponse;
import nl.vintik.lambda.streaming.sse.StreamingState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Handler behaviour: rejections never open the stream or touch the model, and every failure after
 * the stream opens propagates with exactly one status on the wire.
 */
class BedrockStreamHandlerTest {

    private static final String DELIMITER = "\u0000".repeat(8);
    private static final int RUNTIME_API_ACCEPTED = 202;

    /** Channel over an in-memory stream; optionally fails every SSE write after the metadata. */
    private static final class FakeChannel implements ResponseChannel {
        final ByteArrayOutputStream sink = new ByteArrayOutputStream();
        final boolean failBodyWrites;
        int completions = 0;

        FakeChannel(boolean failBodyWrites) {
            this.failBodyWrites = failBodyWrites;
        }

        private final OutputStream out = new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                write(new byte[] {(byte) b}, 0, 1);
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                // Body writes are the only ones that start with an SSE field.
                String chunk = new String(b, off, len, StandardCharsets.UTF_8);
                if (failBodyWrites && (chunk.startsWith("data:") || chunk.startsWith("event:"))) {
                    throw new IOException("client went away");
                }
                sink.write(b, off, len);
            }
        };

        @Override
        public OutputStream getOutput() {
            return out;
        }

        @Override
        public int complete() {
            completions++;
            return RUNTIME_API_ACCEPTED;
        }

        String wire() {
            return sink.toString(StandardCharsets.UTF_8);
        }
    }

    private final ResponseChannelFactory factory = mock(ResponseChannelFactory.class);
    private final Context context = mock(Context.class);

    @BeforeEach
    void setUp() {
        when(context.getAwsRequestId()).thenReturn("req-1");
        when(context.getRemainingTimeInMillis()).thenReturn(60_000);
    }

    @AfterEach
    void clear() {
        StreamingState.reset();
    }

    private static InputStream json(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    private BedrockStreamHandler handler(TokenStream tokens, FakeChannel channel, boolean requireAgent) {
        when(factory.open(anyString())).thenReturn(channel);
        return new BedrockStreamHandler(
                new PromptParser(), tokens, new ResponseWriter(), new StreamingResponse(new ResponseWriter(), factory, requireAgent));
    }

    private static int occurrences(String haystack, String needle) {
        int count = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1)) {
            count++;
        }
        return count;
    }

    @Test
    void givenAnUnsupportedParameterWhenHandledThen400IsBufferedAndNothingStreamsOrReachesTheModel()
            throws IOException {
        TokenStream tokens = mock(TokenStream.class);
        BedrockStreamHandler handler = new BedrockStreamHandler(
                new PromptParser(), tokens, new ResponseWriter(),
                new StreamingResponse(new ResponseWriter(), factory, false));
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        handler.handleRequest(json("{\"prompt\":\"hi\",\"headPadBytes\":10}"), output, context);

        String wire = output.toString(StandardCharsets.UTF_8);
        assertTrue(wire.contains("\"statusCode\":400"), wire);
        assertTrue(wire.contains("headPadBytes"), wire);
        verify(factory, never()).open(anyString());
        verifyNoInteractions(tokens);
    }

    @Test
    void givenAnInvalidBodyWhenHandledThen400IsBufferedAndTheStreamIsNeverOpened() throws IOException {
        TokenStream tokens = mock(TokenStream.class);
        BedrockStreamHandler handler = new BedrockStreamHandler(
                new PromptParser(), tokens, new ResponseWriter(),
                new StreamingResponse(new ResponseWriter(), factory, false));
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        handler.handleRequest(json("{\"body\":\"not json\"}"), output, context);

        assertTrue(output.toString(StandardCharsets.UTF_8).contains("\"statusCode\":400"));
        verify(factory, never()).open(anyString());
        verifyNoInteractions(tokens);
    }

    @Test
    void givenAModelThatStreamsWhenHandledThenDeltasAndTerminalEventFollowOneMetadataBlock()
            throws IOException {
        FakeChannel channel = new FakeChannel(false);
        BedrockStreamHandler handler = handler((request, deadline, sink) -> {
            sink.accept("Hello");
            sink.accept(" world");
        }, channel, false);

        handler.handleRequest(json("{\"prompt\":\"hi\"}"), new ByteArrayOutputStream(), context);

        String wire = channel.wire();
        assertEquals(1, occurrences(wire, "\"statusCode\""));
        assertEquals(1, occurrences(wire, DELIMITER));
        assertTrue(wire.endsWith("data: Hello\n\ndata:  world\n\nevent: end\ndata: [DONE]\n\n"), wire);
        assertEquals(1, channel.completions);
        assertTrue(StreamingState.alreadyAnswered());
    }

    private static void assertSingleStatus(String wire) {
        assertEquals(1, occurrences(wire, "\"statusCode\""), wire);
        assertEquals(1, occurrences(wire, DELIMITER), wire);
        assertFalse(wire.contains("\"statusCode\":502"), wire);
    }

    @Test
    void givenAFailureBeforeTheFirstDeltaWhenHandledThenItPropagatesWithErrorEventAndOneStatus() {
        FakeChannel channel = new FakeChannel(false);
        ModelStreamException failure = new ModelStreamException("boom");
        BedrockStreamHandler handler = handler((request, deadline, sink) -> {
            throw failure;
        }, channel, false);

        ModelStreamException thrown = assertThrows(ModelStreamException.class,
                () -> handler.handleRequest(json("{}"), new ByteArrayOutputStream(), context));

        assertSame(failure, thrown);
        String wire = channel.wire();
        assertSingleStatus(wire);
        assertTrue(wire.contains("event: error\n"), wire);
        assertFalse(wire.contains("boom"), "no model detail may reach the client: " + wire);
        assertFalse(wire.contains("event: end"), wire);
        assertEquals(1, channel.completions);
        assertFalse(StreamingState.alreadyAnswered());
    }

    @Test
    void givenAFailureMidStreamWhenHandledThenDeltasStayAndAnErrorEventFollowsWithoutASecondStatus() {
        FakeChannel channel = new FakeChannel(false);
        BedrockStreamHandler handler = handler((request, deadline, sink) -> {
            sink.accept("partial");
            throw new ModelStreamException("mid-stream");
        }, channel, false);

        assertThrows(ModelStreamException.class,
                () -> handler.handleRequest(json("{}"), new ByteArrayOutputStream(), context));

        String wire = channel.wire();
        assertSingleStatus(wire);
        assertTrue(wire.contains("data: partial\n\n"), wire);
        assertTrue(wire.endsWith("event: error\ndata: The response stream failed.\n\n"), wire);
        assertFalse(StreamingState.alreadyAnswered());
    }

    @Test
    void givenAModelTimeoutWhenHandledThenTheTimeoutPropagatesWithAnErrorEvent() {
        FakeChannel channel = new FakeChannel(false);
        BedrockStreamHandler handler = handler((request, deadline, sink) -> {
            throw new ModelStreamTimeoutException("deadline");
        }, channel, false);

        assertThrows(ModelStreamTimeoutException.class,
                () -> handler.handleRequest(json("{}"), new ByteArrayOutputStream(), context));

        String wire = channel.wire();
        assertSingleStatus(wire);
        assertTrue(wire.contains("event: error\n"), wire);
        assertEquals(1, channel.completions);
    }

    @Test
    void givenWritesThatFailWhenHandledThenTheIoExceptionPropagatesAndTheStreamIsStillCompleted() {
        FakeChannel channel = new FakeChannel(true);
        BedrockStreamHandler handler = handler((request, deadline, sink) -> sink.accept("lost"), channel, false);

        IOException thrown = assertThrows(IOException.class,
                () -> handler.handleRequest(json("{}"), new ByteArrayOutputStream(), context));

        assertEquals("client went away", thrown.getMessage());
        String wire = channel.wire();
        assertSingleStatus(wire);
        assertFalse(wire.contains("data:"), "the failed write must not have reached the wire: " + wire);
        assertEquals(1, channel.completions);
        assertFalse(StreamingState.alreadyAnswered());
    }

    @Test
    void givenNoAgentWhenHandledThenItFailsWithTheDocumentedMessageBeforeAnythingIsWritten() {
        TokenStream tokens = mock(TokenStream.class);
        FakeChannel channel = new FakeChannel(false);
        BedrockStreamHandler handler = handler(tokens, channel, true);

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> handler.handleRequest(json("{}"), new ByteArrayOutputStream(), context));

        assertTrue(thrown.getMessage().contains("Streaming requires the agent"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("-javaagent"), thrown.getMessage());
        verify(factory, never()).open(anyString());
        verifyNoInteractions(tokens);
        assertEquals("", channel.wire());
    }

    /**
     * Handler-level half of Property 6: whatever unsupported member is sent, the model source sees
     * zero interactions and the live stream is never opened.
     */
    @Tag("Feature: java-bedrock-sse-streaming-endpoint, Property 6: unsupported parameters never reach the model")
    @ParameterizedTest
    @ValueSource(strings = {
            "headPadBytes", "tailPadBytes", "headPadEvents", "temperature", "model", "stream", "topP",
            "stop", "system", "x", "PROMPT", "max_tokens"})
    void givenAnUnsupportedMemberWhenHandledThenTheModelIsNeverCalled(String member) throws IOException {
        TokenStream tokens = mock(TokenStream.class);
        BedrockStreamHandler handler = new BedrockStreamHandler(
                new PromptParser(), tokens, new ResponseWriter(),
                new StreamingResponse(new ResponseWriter(), factory, false));
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        handler.handleRequest(json("{\"prompt\":\"hi\",\"" + member + "\":1}"), output, context);

        assertTrue(output.toString(StandardCharsets.UTF_8).contains("\"statusCode\":400"));
        verify(factory, never()).open(anyString());
        verifyNoInteractions(tokens);
    }

    @Test
    void givenAResolvedRequestWhenHandledThenTheModelReceivesTheParsedRequest() throws IOException {
        FakeChannel channel = new FakeChannel(false);
        List<PromptRequest> seen = new ArrayList<>();
        BedrockStreamHandler handler = handler((request, deadline, sink) -> seen.add(request), channel, false);

        handler.handleRequest(json("{\"prompt\":\"tell me\",\"maxTokens\":7}"), new ByteArrayOutputStream(), context);

        assertEquals(List.of(new PromptRequest("tell me", 7)), seen);
    }
}
