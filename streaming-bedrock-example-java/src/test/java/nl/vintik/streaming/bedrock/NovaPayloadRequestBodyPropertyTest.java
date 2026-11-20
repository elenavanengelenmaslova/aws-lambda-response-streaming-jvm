package nl.vintik.streaming.bedrock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Feature: java-bedrock-sse-streaming-endpoint, Property 3: the Nova request body preserves the prompt exactly.
 *
 * <p><b>Validates: Requirements 4.1</b>
 */
class NovaPayloadRequestBodyPropertyTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    static Stream<Arguments> prompts() {
        return Stream.of(
                Arguments.of("plain ascii", "Tell me a story about a lambda.", 1000),
                Arguments.of("double quotes", "He said \"hello\" and \"goodbye\"", 1),
                Arguments.of("backslashes", "C:\\temp\\new\\table \\\\ \\n literal", 5000),
                Arguments.of("newlines", "line one\nline two\r\nline three\r", 250),
                Arguments.of("tabs", "col1\tcol2\t\tcol3", 42),
                Arguments.of("emoji", "Stream \uD83D\uDE80 tokens \uD83D\uDC4D\uD83C\uDFFD now", 1000),
                Arguments.of("right to left", "\u0645\u0631\u062D\u0628\u0627 \u05E9\u05DC\u05D5\u05DD abc", 1000),
                Arguments.of("nul character", "before\u0000after", 1000),
                Arguments.of("control characters", "\u0001\u0002\u001F\u007F end", 1000),
                Arguments.of("json lookalike", "{\"prompt\":\"x\",\"maxTokens\":9}", 1000),
                Arguments.of("json escape text", "\\u0041 \\\" \\/ </script>", 1000),
                Arguments.of("line separators", "a\u2028b\u2029c", 1000),
                Arguments.of("cjk", "\u3053\u3093\u306B\u3061\u306F\u4E16\u754C", 1000),
                Arguments.of("lone surrogate-free mixed", "a\"b\\c\nd\te\uD83D\uDE00\u0000f", 7),
                Arguments.of("empty prompt", "", 1000),
                Arguments.of("single space", " ", 1000),
                Arguments.of("max length 8000", "x".repeat(PromptLimits.MAX_PROMPT_LEN), 5000),
                Arguments.of("max length 8000 hostile", "\"\\\n".repeat(PromptLimits.MAX_PROMPT_LEN / 3), 3));
    }

    @Tag("property")
    @ParameterizedTest(name = "{0}")
    @MethodSource("prompts")
    @DisplayName("Feature: java-bedrock-sse-streaming-endpoint, Property 3: the Nova request body preserves the prompt exactly")
    void requestBodyPreservesPromptExactly(String label, String prompt, int maxTokens) throws IOException {
        String body = NovaPayload.requestBody(prompt, maxTokens);

        JsonNode root = MAPPER.readTree(body);
        assertNotNull(root);
        assertTrue(root.isObject());
        assertEquals("messages-v1", root.path("schemaVersion").asText());

        JsonNode messages = root.path("messages");
        assertEquals(1, messages.size());
        assertEquals("user", messages.get(0).path("role").asText());

        JsonNode content = messages.get(0).path("content");
        assertEquals(1, content.size());
        JsonNode text = content.get(0).path("text");
        assertTrue(text.isTextual());
        assertEquals(prompt, text.textValue());

        JsonNode tokens = root.path("inferenceConfig").path("maxTokens");
        assertTrue(tokens.isInt());
        assertEquals(maxTokens, tokens.intValue());
    }
}
