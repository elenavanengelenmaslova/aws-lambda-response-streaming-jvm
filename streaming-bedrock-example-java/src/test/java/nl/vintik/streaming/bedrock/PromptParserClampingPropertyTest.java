package nl.vintik.streaming.bedrock;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Feature: java-bedrock-sse-streaming-endpoint, Property 4: the token count never exceeds the ceiling.
 *
 * <p><b>Validates: Requirements 3.4, 10.3</b>
 */
class PromptParserClampingPropertyTest {

    private final PromptParser parser = new PromptParser();

    /** Each case is the JSON text of a whole payload; the description names the maxTokens shape. */
    static Stream<Arguments> payloads() {
        int ceiling = PromptLimits.MAX_TOKENS_CEILING;
        return Stream.of(
                Arguments.of("negative", "{\"maxTokens\":-5}"),
                Arguments.of("large negative", "{\"maxTokens\":-2147483648}"),
                Arguments.of("zero", "{\"maxTokens\":0}"),
                Arguments.of("one", "{\"maxTokens\":1}"),
                Arguments.of("mid range", "{\"maxTokens\":2500}"),
                Arguments.of("ceiling", "{\"maxTokens\":" + ceiling + "}"),
                Arguments.of("ceiling plus one", "{\"maxTokens\":" + (ceiling + 1) + "}"),
                Arguments.of("Integer.MAX_VALUE", "{\"maxTokens\":" + Integer.MAX_VALUE + "}"),
                Arguments.of("long beyond int range", "{\"maxTokens\":" + (Integer.MAX_VALUE + 1L) + "}"),
                Arguments.of("Long.MAX_VALUE", "{\"maxTokens\":" + Long.MAX_VALUE + "}"),
                Arguments.of("beyond long range", "{\"maxTokens\":99999999999999999999999999}"),
                Arguments.of("negative beyond long range", "{\"maxTokens\":-99999999999999999999999999}"),
                Arguments.of("fractional", "{\"maxTokens\":12.7}"),
                Arguments.of("fraction below one", "{\"maxTokens\":0.4}"),
                Arguments.of("huge double", "{\"maxTokens\":1e30}"),
                Arguments.of("negative double", "{\"maxTokens\":-1.5e10}"),
                Arguments.of("string", "{\"maxTokens\":\"lots\"}"),
                Arguments.of("numeric string", "{\"maxTokens\":\"99999\"}"),
                Arguments.of("null", "{\"maxTokens\":null}"),
                Arguments.of("absent", "{}"),
                Arguments.of("boolean", "{\"maxTokens\":true}"),
                Arguments.of("proxy event body", "{\"body\":\"{\\\"maxTokens\\\":999999}\"}"));
    }

    @Tag("property")
    @ParameterizedTest(name = "{0}")
    @MethodSource("payloads")
    @DisplayName("`Given any maxTokens value When parsed Then the resolved value stays within bounds (Property 4)`")
    void resolvedMaxTokensStaysWithinBounds(String description, String json) {
        PromptResult result = parser.resolve(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));

        PromptResult.Resolved resolved = assertInstanceOf(PromptResult.Resolved.class, result, description);
        int maxTokens = resolved.request().maxTokens();
        assertTrue(
                maxTokens >= 1 && maxTokens <= PromptLimits.MAX_TOKENS_CEILING,
                description + " resolved to " + maxTokens);
    }
}
