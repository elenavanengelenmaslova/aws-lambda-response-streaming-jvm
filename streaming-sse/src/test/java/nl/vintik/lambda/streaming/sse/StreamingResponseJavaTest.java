package nl.vintik.lambda.streaming.sse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import nl.vintik.lambda.streaming.ResponseMetadata;
import nl.vintik.lambda.streaming.ResponseWriter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Proves {@link StreamingResponse#stream} is usable from plain Java: Java lambdas for the channel
 * factory and the body, the two-argument {@link ResponseMetadata} constructor, and a checked
 * {@link IOException} that propagates unchanged.
 */
class StreamingResponseJavaTest {

    private static final ResponseMetadata METADATA =
            new ResponseMetadata(200, Map.of("Content-Type", "text/event-stream"));

    /** In-memory channel recording what was written and how often it was completed. */
    private static final class FakeChannel implements ResponseChannel {
        final ByteArrayOutputStream sink = new ByteArrayOutputStream();
        int completions = 0;

        @Override
        public OutputStream getOutput() {
            return sink;
        }

        @Override
        public int complete() {
            completions++;
            return ResponseChannelKt.RUNTIME_API_ACCEPTED;
        }

        String body() {
            return sink.toString(StandardCharsets.UTF_8);
        }
    }

    @AfterEach
    void clear() {
        StreamingState.reset();
    }

    @Test
    void givenABodyWhenStreamedThenMetadataPrecedesBodyAndInvocationIsAnswered() throws IOException {
        FakeChannel channel = new FakeChannel();
        StreamingResponse response =
                new StreamingResponse(new ResponseWriter(), requestId -> channel, false);

        response.stream("req-1", METADATA, out -> out.write("payload".getBytes(StandardCharsets.UTF_8)));

        String body = channel.body();
        assertTrue(body.startsWith("{"), "metadata must be written first, got: " + body);
        assertTrue(body.contains("\"statusCode\":200"), "metadata must carry the status, got: " + body);
        assertTrue(body.contains("\u0000".repeat(8)), "the 8-byte delimiter must be present");
        assertTrue(body.endsWith("payload"), "the body must follow the delimiter, got: " + body);
        assertEquals(1, channel.completions);
        assertTrue(StreamingState.alreadyAnswered());
    }

    @Test
    void givenABodyThatThrowsWhenStreamedThenChannelCompletesNotAnsweredAndSameIoExceptionPropagates() {
        FakeChannel channel = new FakeChannel();
        StreamingResponse response =
                new StreamingResponse(new ResponseWriter(), requestId -> channel, false);
        IOException failure = new IOException("model stream broke");

        IOException thrown = assertThrows(IOException.class, () -> response.stream("req-2", METADATA, out -> {
            out.write("partial".getBytes(StandardCharsets.UTF_8));
            throw failure;
        }));

        assertSame(failure, thrown);
        assertEquals(1, channel.completions, "the channel must still be completed");
        assertFalse(StreamingState.alreadyAnswered(), "a failed body must not mark the invocation answered");
        assertTrue(channel.body().endsWith("partial"));
    }
}
