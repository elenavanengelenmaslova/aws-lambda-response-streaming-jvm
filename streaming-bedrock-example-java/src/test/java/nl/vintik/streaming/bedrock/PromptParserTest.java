package nl.vintik.streaming.bedrock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import nl.vintik.lambda.streaming.ResponseWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** One test group per row of the design's parser table. */
class PromptParserTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String INVALID_JSON = "The request body is not valid JSON.";

    private final PromptParser parser = new PromptParser();

    // ---- helpers ------------------------------------------------------------------------------

    private PromptResult resolveRaw(String document) {
        return parser.resolve(new ByteArrayInputStream(document.getBytes(StandardCharsets.UTF_8)));
    }

    /** Wraps {@code body} (a string, or null for JSON null) in an API Gateway proxy event. */
    private PromptResult resolveProxy(String body) {
        ObjectNode event = MAPPER.createObjectNode();
        event.put("httpMethod", "POST");
        if (body == null) {
            event.putNull("body");
        } else {
            event.put("body", body);
        }
        return resolveRaw(event.toString());
    }

    private static PromptRequest resolved(PromptResult result) {
        return assertInstanceOf(PromptResult.Resolved.class, result).request();
    }

    private static PromptResult.Rejected rejected(PromptResult result) {
        return assertInstanceOf(PromptResult.Rejected.class, result);
    }

    private static void assertDefaults(PromptResult result) {
        PromptRequest request = resolved(result);
        assertEquals(PromptLimits.DEFAULT_PROMPT, request.prompt());
        assertEquals(PromptLimits.DEFAULT_MAX_TOKENS, request.maxTokens());
    }

    // ---- no body, empty body, body: null -> defaults --------------------------------------------

    @Test
    void emptyInputUsesDefaults() {
        assertDefaults(resolveRaw(""));
    }

    @Test
    void documentWithoutBodyMemberIsThePayloadItself() {
        // No `body` member means the document is the bare payload (design table), so an empty
        // object uses defaults and any non-payload member such as `httpMethod` is unsupported.
        assertDefaults(resolveRaw("{}"));
        assertEquals("Unsupported parameter: httpMethod",
                rejected(resolveRaw("{\"httpMethod\":\"POST\"}")).message());
    }

    @Test
    void proxyEventWithEmptyBodyUsesDefaults() {
        assertDefaults(resolveProxy(""));
    }

    @Test
    void proxyEventWithBlankBodyUsesDefaults() {
        assertDefaults(resolveProxy("   "));
    }

    @Test
    void proxyEventWithNullBodyUsesDefaults() {
        assertDefaults(resolveRaw("{\"httpMethod\":\"POST\",\"body\":null}"));
        assertDefaults(resolveProxy(null));
    }

    // ---- body present but not a JSON object -> 400 ----------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"not json", "{\"prompt\":", "[1,2]", "\"text\"", "42", "true"})
    void proxyBodyThatIsNotAJsonObjectIsRejected(String body) {
        PromptResult.Rejected rejection = rejected(resolveProxy(body));
        assertEquals(400, rejection.status());
        assertEquals(INVALID_JSON, rejection.message());
    }

    @Test
    void proxyBodyThatIsNotAStringIsRejected() {
        PromptResult.Rejected rejection = rejected(resolveRaw("{\"body\":{\"prompt\":\"hi\"}}"));
        assertEquals(400, rejection.status());
        assertEquals(INVALID_JSON, rejection.message());
    }

    @ParameterizedTest
    @ValueSource(strings = {"garbage", "{\"prompt\":", "[1]", "7"})
    void bareDocumentThatIsNotAJsonObjectIsRejected(String document) {
        PromptResult.Rejected rejection = rejected(resolveRaw(document));
        assertEquals(400, rejection.status());
        assertEquals(INVALID_JSON, rejection.message());
    }

    // ---- payload forms ----------------------------------------------------------------------------

    @Test
    void bareObjectIsTheTreatedAsThePayload() {
        PromptRequest request = resolved(resolveRaw("{\"prompt\":\"hello\",\"maxTokens\":50}"));
        assertEquals("hello", request.prompt());
        assertEquals(50, request.maxTokens());
    }

    @Test
    void proxyBodyIsTheTreatedAsThePayload() {
        PromptRequest request = resolved(resolveProxy("{\"prompt\":\"hello\",\"maxTokens\":50}"));
        assertEquals("hello", request.prompt());
        assertEquals(50, request.maxTokens());
    }

    @Test
    void emptyObjectUsesDefaults() {
        assertDefaults(resolveProxy("{}"));
    }

    // ---- unsupported member -> 400 naming it ------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"temperature", "headPadBytes", "tailPadBytes", "headPadEvents", "Prompt", ""})
    void unsupportedMemberIsRejectedByName(String name) {
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("prompt", "hello");
        payload.put(name, 1);
        PromptResult.Rejected rejection = rejected(resolveProxy(payload.toString()));
        assertEquals(400, rejection.status());
        assertEquals("Unsupported parameter: " + name, rejection.message());
    }

    @Test
    void unsupportedMemberIsRejectedOnABarePayloadToo() {
        PromptResult.Rejected rejection = rejected(resolveRaw("{\"stop\":[\"x\"]}"));
        assertEquals(400, rejection.status());
        assertEquals("Unsupported parameter: stop", rejection.message());
    }

    @Test
    void unsupportedMemberWinsOverOtherProblems() {
        // The prompt is also invalid, but the unsupported member is reported and nothing else is processed.
        PromptResult.Rejected rejection = rejected(resolveRaw("{\"prompt\":5,\"extra\":true}"));
        assertEquals("Unsupported parameter: extra", rejection.message());
    }

    @Test
    void exactly64CharacterNameIsNotTruncated() {
        String name = "n".repeat(64);
        PromptResult.Rejected rejection = rejected(resolveRaw("{\"" + name + "\":1}"));
        assertEquals("Unsupported parameter: " + name, rejection.message());
    }

    @Test
    void tenThousandCharacterNameIsTruncatedTo64() {
        String name = "x".repeat(10_000);
        PromptResult.Rejected rejection = rejected(resolveRaw("{\"" + name + "\":1}"));
        assertEquals(400, rejection.status());
        assertEquals("Unsupported parameter: " + "x".repeat(64), rejection.message());
    }

    @Test
    void hostileParameterNameKeepsTheSerialisedErrorValidJson() throws IOException {
        String hostile = "a\"b\\c\nd\r\te\u0000f</script>{\"x\":1}\u2028";
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put(hostile, 1);
        PromptResult.Rejected rejection = rejected(resolveProxy(payload.toString()));
        assertEquals("Unsupported parameter: " + hostile, rejection.message());

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new ResponseWriter().writeError(out, rejection.status(), rejection.message());

        // metadata JSON, 8 null bytes, then the JSON error body
        byte[] bytes = out.toByteArray();
        int delimiter = indexOfDelimiter(bytes);
        JsonNode metadata = MAPPER.readTree(slice(bytes, 0, delimiter));
        JsonNode body = MAPPER.readTree(slice(bytes, delimiter + 8, bytes.length));

        assertEquals(400, metadata.get("statusCode").asInt());
        assertEquals("application/json", metadata.get("headers").get("Content-Type").asText());
        assertEquals(rejection.message(), body.get("message").asText());
    }

    // ---- prompt ------------------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"5", "true", "1.5", "[\"a\"]", "{\"a\":1}"})
    void nonStringPromptIsRejected(String promptJson) {
        PromptResult.Rejected rejection = rejected(resolveRaw("{\"prompt\":" + promptJson + "}"));
        assertEquals(400, rejection.status());
        assertEquals("The prompt must be a string.", rejection.message());
    }

    @Test
    void nullPromptUsesTheDefaultPrompt() {
        assertDefaults(resolveRaw("{\"prompt\":null}"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "\t\n  "})
    void blankPromptUsesTheDefaultPrompt(String prompt) {
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("prompt", prompt);
        assertEquals(PromptLimits.DEFAULT_PROMPT, resolved(resolveProxy(payload.toString())).prompt());
    }

    @Test
    void promptAtTheLimitIsAccepted() {
        String prompt = "p".repeat(PromptLimits.MAX_PROMPT_LEN);
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("prompt", prompt);
        assertEquals(prompt, resolved(resolveProxy(payload.toString())).prompt());
    }

    @Test
    void promptOverTheLimitIsRejected() {
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("prompt", "p".repeat(PromptLimits.MAX_PROMPT_LEN + 1));
        PromptResult.Rejected rejection = rejected(resolveProxy(payload.toString()));
        assertEquals(400, rejection.status());
        assertEquals("The prompt is too long.", rejection.message());
    }

    // ---- maxTokens ---------------------------------------------------------------------------------

    @Test
    void absentMaxTokensUsesTheDefault() {
        assertEquals(PromptLimits.DEFAULT_MAX_TOKENS, resolved(resolveRaw("{\"prompt\":\"hi\"}")).maxTokens());
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"lots\"", "\"100\"", "null", "true", "[1]", "{}"})
    void nonNumericMaxTokensUsesTheDefault(String value) {
        PromptRequest request = resolved(resolveRaw("{\"maxTokens\":" + value + "}"));
        assertEquals(PromptLimits.DEFAULT_MAX_TOKENS, request.maxTokens());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "-5", "0", "1", "2", "5000", "4999", "5001", "2147483647", "2147483648",
        "9223372036854775807", "99999999999999999999999", "-99999999999999999999999",
        "0.4", "2.7", "1e10", "-1e10"
    })
    void maxTokensIsClampedIntoRange(String value) {
        int tokens = resolved(resolveRaw("{\"maxTokens\":" + value + "}")).maxTokens();
        assertTrue(tokens >= 1 && tokens <= PromptLimits.MAX_TOKENS_CEILING, "out of range: " + tokens);
    }

    @Test
    void maxTokensClampingBoundariesAreExact() {
        assertEquals(1, resolved(resolveRaw("{\"maxTokens\":-5}")).maxTokens());
        assertEquals(1, resolved(resolveRaw("{\"maxTokens\":0}")).maxTokens());
        assertEquals(1, resolved(resolveRaw("{\"maxTokens\":-99999999999999999999999}")).maxTokens());
        assertEquals(1234, resolved(resolveRaw("{\"maxTokens\":1234}")).maxTokens());
        assertEquals(PromptLimits.MAX_TOKENS_CEILING, resolved(resolveRaw("{\"maxTokens\":5001}")).maxTokens());
        assertEquals(PromptLimits.MAX_TOKENS_CEILING,
                resolved(resolveRaw("{\"maxTokens\":99999999999999999999999}")).maxTokens());
    }

    // ---- byte helpers ---------------------------------------------------------------------------------

    private static int indexOfDelimiter(byte[] bytes) {
        for (int i = 0; i + 8 <= bytes.length; i++) {
            boolean allZero = true;
            for (int j = 0; j < 8; j++) {
                if (bytes[i + j] != 0) {
                    allZero = false;
                    break;
                }
            }
            if (allZero) {
                return i;
            }
        }
        throw new AssertionError("no 8-null-byte delimiter in output");
    }

    private static byte[] slice(byte[] bytes, int from, int to) {
        return Arrays.copyOfRange(bytes, from, to);
    }
}
