package nl.vintik.streaming.bedrock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.amazonaws.services.lambda.runtime.Context;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import nl.vintik.lambda.streaming.ResponseWriter;
import nl.vintik.lambda.streaming.sse.ResponseChannel;
import nl.vintik.lambda.streaming.sse.StreamingResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Relay properties of {@link BedrockStreamHandler}: the real handler, the real
 * {@link StreamingResponse} (agent check off) and an in-memory response channel, fed by a stub
 * {@link TokenStream}. No model, no network.
 */
class BedrockStreamHandlerRelayPropertyTest {

    private static final int DELIMITER_LENGTH = 8;
    private static final int PER_EVENT_OVERHEAD = 8;
    private static final int PER_NEWLINE_OVERHEAD = 7;
    /** {@code event: end\ndata: [DONE]\n\n}. */
    private static final int TERMINAL_EVENT_BYTES = 26;

    /** An in-memory channel recording everything the handler writes to the live stream. */
    private static final class RecordingChannel implements ResponseChannel {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private int completions;

        @Override
        public OutputStream getOutput() {
            return bytes;
        }

        @Override
        public int complete() {
            completions++;
            return 202;
        }
    }

    private record Sse(String event, String data) {
    }

    static Stream<List<String>> deltaSequences() {
        return Stream.of(
                List.of(),
                List.of("Hello"),
                List.of("Hello", " ", "world", "!"),
                List.of("", "", "x", ""),
                List.of("line one\nline two\nline three"),
                List.of("trailing newline\n", "\nleading newline", "\n\n"),
                List.of("naïve café", "日本語のテキスト", "مرحبا بالعالم"),
                List.of("emoji 😀🚀", "👨‍👩‍👧‍👦", "\u00e9\u0301"),
                List.of("data: looks like a field", "event: end", "[DONE]"),
                List.of("tabs\tand  spaces ", " leading space"),
                List.of("x".repeat(1_000_000)),
                List.of(("long line ".repeat(1_000) + "\n").repeat(50)),
                repeated("a", 2_000),
                repeated("tok\n", 500),
                List.of("\"quotes\" and \\backslashes\\ and {json: [1,2]}"),
                List.of("", "a\n", "", "\nb"),
                List.of("\u0000nul", "\u2028line sep", "\u007fdel"),
                List.of("one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten"));
    }

    private static List<String> repeated(String value, int times) {
        List<String> list = new ArrayList<>(times);
        for (int i = 0; i < times; i++) {
            list.add(value);
        }
        return list;
    }

    /** Runs the real handler and returns the bytes written after the metadata delimiter. */
    private static byte[] relay(List<String> deltas) throws IOException {
        RecordingChannel channel = new RecordingChannel();
        StreamingResponse streaming = new StreamingResponse(new ResponseWriter(), id -> channel, false);
        TokenStream source = (request, deadline, sink) -> {
            for (String delta : deltas) {
                sink.accept(delta);
            }
        };
        BedrockStreamHandler handler =
                new BedrockStreamHandler(new PromptParser(), source, new ResponseWriter(), streaming);

        Context context = mock(Context.class);
        when(context.getAwsRequestId()).thenReturn("req-1");
        when(context.getRemainingTimeInMillis()).thenReturn(60_000);

        ByteArrayOutputStream runtimeOutput = new ByteArrayOutputStream();
        handler.handleRequest(
                new ByteArrayInputStream("{}".getBytes(StandardCharsets.UTF_8)), runtimeOutput, context);

        assertEquals(0, runtimeOutput.size(), "the runtime-provided stream is not used for a streamed answer");
        assertEquals(1, channel.completions, "the response is completed exactly once");

        byte[] all = channel.bytes.toByteArray();
        int bodyStart = indexAfterDelimiter(all);
        assertTrue(bodyStart > 0, "metadata and the 8-byte delimiter precede the body");
        byte[] body = new byte[all.length - bodyStart];
        System.arraycopy(all, bodyStart, body, 0, body.length);
        return body;
    }

    private static int indexAfterDelimiter(byte[] all) {
        for (int i = 0; i + DELIMITER_LENGTH <= all.length; i++) {
            boolean allZero = true;
            for (int j = 0; j < DELIMITER_LENGTH; j++) {
                if (all[i + j] != 0) {
                    allZero = false;
                    break;
                }
            }
            if (allZero) {
                return i + DELIMITER_LENGTH;
            }
        }
        return -1;
    }

    /**
     * Minimal conforming SSE parser: lines end in LF, a blank line dispatches, {@code field: value}
     * drops a single leading space, and multiple {@code data} lines join with LF.
     */
    private static List<Sse> parseSse(byte[] body) {
        String text = new String(body, StandardCharsets.UTF_8);
        List<Sse> events = new ArrayList<>();
        String name = "message";
        List<String> data = new ArrayList<>();
        boolean any = false;
        int pos = 0;
        while (pos < text.length()) {
            int eol = text.indexOf('\n', pos);
            assertNotEquals(-1, eol, "every line is LF-terminated");
            String line = text.substring(pos, eol);
            pos = eol + 1;
            if (line.isEmpty()) {
                if (any) {
                    events.add(new Sse(name, String.join("\n", data)));
                }
                name = "message";
                data = new ArrayList<>();
                any = false;
                continue;
            }
            int colon = line.indexOf(':');
            String field = colon < 0 ? line : line.substring(0, colon);
            String value = colon < 0 ? "" : line.substring(colon + 1);
            if (value.startsWith(" ")) {
                value = value.substring(1);
            }
            if (field.equals("data")) {
                data.add(value);
                any = true;
            } else if (field.equals("event")) {
                name = value;
                any = true;
            }
        }
        assertTrue(data.isEmpty() && !any, "the stream ends on a completed event");
        return events;
    }

    @Tag("property")
    @ParameterizedTest(name = "Property 1: SSE relay round-trips the model''s text #{index}")
    @MethodSource("deltaSequences")
    void property1_sseRelayRoundTripsModelText(List<String> deltas) throws IOException {
        // Feature: java-bedrock-sse-streaming-endpoint, Property 1: SSE relay round-trips the model's text
        // Validates: Requirements 4.2, 4.4, 12.2
        List<Sse> events = parseSse(relay(deltas));

        assertEquals(deltas.size() + 1, events.size(), "one event per delta plus the terminal event");

        StringBuilder reassembled = new StringBuilder();
        for (int i = 0; i < deltas.size(); i++) {
            assertEquals("message", events.get(i).event());
            assertEquals(deltas.get(i), events.get(i).data(), "delta " + i + " round-trips exactly");
            reassembled.append(events.get(i).data());
        }
        assertEquals(String.join("", deltas), reassembled.toString());

        Sse terminal = events.get(events.size() - 1);
        assertEquals("end", terminal.event());
        assertEquals("[DONE]", terminal.data());
    }

    /**
     * Conforming parser that treats CR, CRLF and LF as line terminators, as the SSE specification
     * requires. The lenient {@link #parseSse} above would hide a CR that injects a field.
     */
    private static List<Sse> parseSseAnyLineEnding(byte[] body) {
        String text = new String(body, StandardCharsets.UTF_8);
        List<Sse> events = new ArrayList<>();
        String name = "message";
        List<String> data = new ArrayList<>();
        boolean any = false;
        for (String line : text.split("\r\n|\r|\n", -1)) {
            if (line.isEmpty()) {
                if (any) {
                    events.add(new Sse(name, String.join("\n", data)));
                }
                name = "message";
                data = new ArrayList<>();
                any = false;
                continue;
            }
            int colon = line.indexOf(':');
            String field = colon < 0 ? line : line.substring(0, colon);
            String value = colon < 0 ? "" : line.substring(colon + 1);
            if (value.startsWith(" ")) {
                value = value.substring(1);
            }
            if (field.equals("data")) {
                data.add(value);
                any = true;
            } else if (field.equals("event")) {
                name = value;
                any = true;
            }
        }
        return events;
    }

    @ParameterizedTest(name = "carriage returns in a delta cannot inject SSE fields #{index}")
    @MethodSource("carriageReturnDeltas")
    @DisplayName("`Given a delta with CR or CRLF When relayed Then a conforming parser sees one message event with LF line endings`")
    void carriageReturnsInDeltasCannotAlterEventFraming(String delta) throws IOException {
        List<Sse> events = parseSseAnyLineEnding(relay(List.of(delta)));
        assertEquals(2, events.size(), "one event for the delta plus the terminal event");
        assertEquals("message", events.get(0).event(), "no field injected through a CR");
        assertEquals(delta.replace("\r\n", "\n").replace('\r', '\n'), events.get(0).data());
        assertEquals("end", events.get(1).event());
        assertEquals("[DONE]", events.get(1).data());
    }

    static Stream<String> carriageReturnDeltas() {
        return Stream.of(
                "line one\rline two",
                "line one\r\nline two",
                "trailing\r",
                "\r",
                "\r\n\r\n",
                "evil\rdata: injected\r\revent: end",
                "evil\r\nevent: error\r\ndata: gone",
                "mixed\r\n\rand\nmore");
    }

    @Test
    @DisplayName("`Given a CRLF split across two deltas When relayed Then the client sees exactly one newline`")
    void crlfSplitAcrossDeltasYieldsOneNewline() throws IOException {
        List<Sse> events = parseSseAnyLineEnding(relay(List.of("left\r", "", "\nright")));
        StringBuilder text = new StringBuilder();
        for (Sse event : events) {
            if (event.event().equals("message")) {
                text.append(event.data());
            }
        }
        assertEquals("left\nright", text.toString());
        assertEquals("end", events.get(events.size() - 1).event());
    }

    @Test
    @DisplayName("`Given a CR ending one delta and text starting the next When relayed Then no extra newline is dropped`")
    void crFollowedByNonLfKeepsBothCharacters() throws IOException {
        List<Sse> events = parseSseAnyLineEnding(relay(List.of("a\r", "b", "\nc")));
        StringBuilder text = new StringBuilder();
        events.stream().filter(e -> e.event().equals("message")).forEach(e -> text.append(e.data()));
        assertEquals("a\nb\nc", text.toString());
    }

    @Tag("property")
    @ParameterizedTest(name = "Property 2: bytes on the wire stay proportional to the answer #{index}")
    @MethodSource("deltaSequences")
    void property2_bytesStayProportionalToAnswer(List<String> deltas) throws IOException {
        // Feature: java-bedrock-sse-streaming-endpoint, Property 2: bytes on the wire stay proportional to the answer
        // Validates: Requirements 11.4, 12.2
        byte[] body = relay(deltas);

        long answerBytes = 0;
        long newlines = 0;
        for (String delta : deltas) {
            answerBytes += delta.getBytes(StandardCharsets.UTF_8).length;
            newlines += delta.chars().filter(c -> c == '\n').count();
        }
        long allowed = answerBytes
                + (long) PER_EVENT_OVERHEAD * deltas.size()
                + PER_NEWLINE_OVERHEAD * newlines
                + TERMINAL_EVENT_BYTES;

        assertTrue(
                body.length <= allowed,
                "body of " + body.length + " bytes exceeds the allowance of " + allowed);
        assertTrue(body.length >= answerBytes - newlines, "the answer itself is on the wire");
    }
}
