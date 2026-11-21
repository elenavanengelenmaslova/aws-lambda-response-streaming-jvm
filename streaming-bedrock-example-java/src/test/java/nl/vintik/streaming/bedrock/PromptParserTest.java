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
import org.junit.jupiter.api.DisplayName;
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

    @ParameterizedTest
    @ValueSource(strings = {
        "{\"prompt\":\"a\"} {\"prompt\":\"b\"}",
        "{\"prompt\":\"a\"}x",
        "{\"prompt\":\"a\"}{}",
        "{} []",
    })
    @DisplayName("`Given non whitespace content after the JSON object When parsed Then it is rejected with 400`")
    void trailingContentAfterTheObjectIsRejected(String document) {
        PromptResult direct = parser.resolve(new ByteArrayInputStream(document.getBytes(StandardCharsets.UTF_8)));
        PromptResult.Rejected rejected = assertInstanceOf(PromptResult.Rejected.class, direct);
        assertEquals(400, rejected.status());
        ObjectNode event = new ObjectMapper().createObjectNode();
        event.put("body", document);
        PromptResult proxied = parser.resolve(
                new ByteArrayInputStream(event.toString().getBytes(StandardCharsets.UTF_8)));
        assertEquals(400, assertInstanceOf(PromptResult.Rejected.class, proxied).status());
    }

    @Test
    @DisplayName("`Given a valid object followed by whitespace When parsed Then it is accepted`")
    void trailingWhitespaceAfterTheObjectIsAccepted() {
        String document = "{\"prompt\":\"hello\"}  \n\t ";
        PromptResult direct = parser.resolve(new ByteArrayInputStream(document.getBytes(StandardCharsets.UTF_8)));
        assertEquals("hello", assertInstanceOf(PromptResult.Resolved.class, direct).request().prompt());
        ObjectNode event = new ObjectMapper().createObjectNode();
        event.put("body", document);
        PromptResult proxied = parser.resolve(
                new ByteArrayInputStream(event.toString().getBytes(StandardCharsets.UTF_8)));
        assertEquals("hello", assertInstanceOf(PromptResult.Resolved.class, proxied).request().prompt());
    }

    @Test
    @DisplayName("`Given an empty input When parsed Then the default prompt and token count are used`")
    void emptyInputUsesDefaults() {
        assertDefaults(resolveRaw(""));
    }

    @Test
    @DisplayName("`Given a document without a body member When parsed Then the document itself is the payload`")
    void documentWithoutBodyMemberIsThePayloadItself() {
        // No `body` member means the document is the bare payload (design table), so an empty
        // object uses defaults and any non-payload member such as `httpMethod` is unsupported.
        assertDefaults(resolveRaw("{}"));
        assertEquals("Unsupported parameter: httpMethod",
                rejected(resolveRaw("{\"httpMethod\":\"POST\"}")).message());
    }

    @Test
    @DisplayName("`Given a proxy event with an empty body When parsed Then the defaults are used`")
    void proxyEventWithEmptyBodyUsesDefaults() {
        assertDefaults(resolveProxy(""));
    }

    @Test
    @DisplayName("`Given a proxy event with a blank body When parsed Then the defaults are used`")
    void proxyEventWithBlankBodyUsesDefaults() {
        assertDefaults(resolveProxy("   "));
    }

    @Test
    @DisplayName("`Given a proxy event with a null body When parsed Then the defaults are used`")
    void proxyEventWithNullBodyUsesDefaults() {
        assertDefaults(resolveRaw("{\"httpMethod\":\"POST\",\"body\":null}"));
        assertDefaults(resolveProxy(null));
    }

    // ---- body present but not a JSON object -> 400 ----------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"not json", "{\"prompt\":", "[1,2]", "\"text\"", "42", "true"})
    @DisplayName("`Given a proxy body that is not a JSON object When parsed Then it is rejected with 400`")
    void proxyBodyThatIsNotAJsonObjectIsRejected(String body) {
        PromptResult.Rejected rejection = rejected(resolveProxy(body));
        assertEquals(400, rejection.status());
        assertEquals(INVALID_JSON, rejection.message());
    }

    @Test
    @DisplayName("`Given a proxy body that is not a string When parsed Then it is rejected with 400`")
    void proxyBodyThatIsNotAStringIsRejected() {
        PromptResult.Rejected rejection = rejected(resolveRaw("{\"body\":{\"prompt\":\"hi\"}}"));
        assertEquals(400, rejection.status());
        assertEquals(INVALID_JSON, rejection.message());
    }

    @ParameterizedTest
    @ValueSource(strings = {"garbage", "{\"prompt\":", "[1]", "7"})
    @DisplayName("`Given a bare document that is not a JSON object When parsed Then it is rejected with 400`")
    void bareDocumentThatIsNotAJsonObjectIsRejected(String document) {
        PromptResult.Rejected rejection = rejected(resolveRaw(document));
        assertEquals(400, rejection.status());
        assertEquals(INVALID_JSON, rejection.message());
    }

    // ---- payload forms ----------------------------------------------------------------------------

    @Test
    @DisplayName("`Given a bare JSON object When parsed Then it is treated as the payload`")
    void bareObjectIsTheTreatedAsThePayload() {
        PromptRequest request = resolved(resolveRaw("{\"prompt\":\"hello\",\"maxTokens\":50}"));
        assertEquals("hello", request.prompt());
        assertEquals(50, request.maxTokens());
    }

    @Test
    @DisplayName("`Given a proxy event body When parsed Then the body is treated as the payload`")
    void proxyBodyIsTheTreatedAsThePayload() {
        PromptRequest request = resolved(resolveProxy("{\"prompt\":\"hello\",\"maxTokens\":50}"));
        assertEquals("hello", request.prompt());
        assertEquals(50, request.maxTokens());
    }

    @Test
    @DisplayName("`Given an empty JSON object When parsed Then the defaults are used`")
    void emptyObjectUsesDefaults() {
        assertDefaults(resolveProxy("{}"));
    }

    // ---- unsupported member -> 400 naming it ------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"temperature", "headPadBytes", "tailPadBytes", "headPadEvents", "Prompt", ""})
    @DisplayName("`Given an unsupported member When parsed Then it is rejected naming the member`")
    void unsupportedMemberIsRejectedByName(String name) {
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("prompt", "hello");
        payload.put(name, 1);
        PromptResult.Rejected rejection = rejected(resolveProxy(payload.toString()));
        assertEquals(400, rejection.status());
        assertEquals("Unsupported parameter: " + name, rejection.message());
    }

    @Test
    @DisplayName("`Given an unsupported member on a bare payload When parsed Then it is rejected too`")
    void unsupportedMemberIsRejectedOnABarePayloadToo() {
        PromptResult.Rejected rejection = rejected(resolveRaw("{\"stop\":[\"x\"]}"));
        assertEquals(400, rejection.status());
        assertEquals("Unsupported parameter: stop", rejection.message());
    }

    @Test
    @DisplayName("`Given an unsupported member and an invalid prompt When parsed Then the unsupported member is reported`")
    void unsupportedMemberWinsOverOtherProblems() {
        // The prompt is also invalid, but the unsupported member is reported and nothing else is processed.
        PromptResult.Rejected rejection = rejected(resolveRaw("{\"prompt\":5,\"extra\":true}"));
        assertEquals("Unsupported parameter: extra", rejection.message());
    }

    @Test
    @DisplayName("`Given a 64 character member name When parsed Then the name is not truncated`")
    void exactly64CharacterNameIsNotTruncated() {
        String name = "n".repeat(64);
        PromptResult.Rejected rejection = rejected(resolveRaw("{\"" + name + "\":1}"));
        assertEquals("Unsupported parameter: " + name, rejection.message());
    }

    @Test
    @DisplayName("`Given a 10000 character member name When parsed Then the name is truncated to 64`")
    void tenThousandCharacterNameIsTruncatedTo64() {
        String name = "x".repeat(10_000);
        PromptResult.Rejected rejection = rejected(resolveRaw("{\"" + name + "\":1}"));
        assertEquals(400, rejection.status());
        assertEquals("Unsupported parameter: " + "x".repeat(64), rejection.message());
    }

    @Test
    @DisplayName("`Given a hostile member name When the rejection is serialised Then the error response is valid JSON`")
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
    @DisplayName("`Given a prompt that is not a string When parsed Then it is rejected`")
    void nonStringPromptIsRejected(String promptJson) {
        PromptResult.Rejected rejection = rejected(resolveRaw("{\"prompt\":" + promptJson + "}"));
        assertEquals(400, rejection.status());
        assertEquals("The prompt must be a string.", rejection.message());
    }

    @Test
    @DisplayName("`Given a null prompt When parsed Then the default prompt is used`")
    void nullPromptUsesTheDefaultPrompt() {
        assertDefaults(resolveRaw("{\"prompt\":null}"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "\t\n  "})
    @DisplayName("`Given a blank prompt When parsed Then the default prompt is used`")
    void blankPromptUsesTheDefaultPrompt(String prompt) {
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("prompt", prompt);
        assertEquals(PromptLimits.DEFAULT_PROMPT, resolved(resolveProxy(payload.toString())).prompt());
    }

    @Test
    @DisplayName("`Given a prompt at the length limit When parsed Then it is accepted`")
    void promptAtTheLimitIsAccepted() {
        String prompt = "p".repeat(PromptLimits.MAX_PROMPT_LEN);
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("prompt", prompt);
        assertEquals(prompt, resolved(resolveProxy(payload.toString())).prompt());
    }

    @Test
    @DisplayName("`Given a prompt over the length limit When parsed Then it is rejected`")
    void promptOverTheLimitIsRejected() {
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("prompt", "p".repeat(PromptLimits.MAX_PROMPT_LEN + 1));
        PromptResult.Rejected rejection = rejected(resolveProxy(payload.toString()));
        assertEquals(400, rejection.status());
        assertEquals("The prompt is too long.", rejection.message());
    }

    // ---- maxTokens ---------------------------------------------------------------------------------

    @Test
    @DisplayName("`Given no maxTokens When parsed Then the default is used`")
    void absentMaxTokensUsesTheDefault() {
        assertEquals(PromptLimits.DEFAULT_MAX_TOKENS, resolved(resolveRaw("{\"prompt\":\"hi\"}")).maxTokens());
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"lots\"", "\"100\"", "null", "true", "[1]", "{}"})
    @DisplayName("`Given a non numeric maxTokens When parsed Then the default is used`")
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
    @DisplayName("`Given an out of range maxTokens When parsed Then it is clamped into range`")
    void maxTokensIsClampedIntoRange(String value) {
        int tokens = resolved(resolveRaw("{\"maxTokens\":" + value + "}")).maxTokens();
        assertTrue(tokens >= 1 && tokens <= PromptLimits.MAX_TOKENS_CEILING, "out of range: " + tokens);
    }

    @Test
    @DisplayName("`Given the clamping boundaries When parsed Then the exact boundary values result`")
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
