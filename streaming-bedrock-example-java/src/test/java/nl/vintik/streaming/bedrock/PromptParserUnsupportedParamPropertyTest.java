package nl.vintik.streaming.bedrock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Feature: java-bedrock-sse-streaming-endpoint, Property 6: unsupported parameters never reach the model.
 *
 * <p>Parser level: any member outside {@code {prompt, maxTokens}} yields {@code Rejected(400, ...)}
 * naming the member. The handler-level zero-model-interaction half is asserted in the handler tests.
 *
 * <p><b>Validates: Requirements 3.6</b>
 */
@Tag("Feature: java-bedrock-sse-streaming-endpoint, Property 6: unsupported parameters never reach the model")
class PromptParserUnsupportedParamPropertyTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_NAME_LEN = 64;

    private final PromptParser parser = new PromptParser();

    @ParameterizedTest(name = "Given member [{0}] on a bare payload When parsed Then it is rejected")
    @ValueSource(strings = {
        "headPadBytes", "tailPadBytes", "headPadEvents", "temperature", "model", "modelId",
        "topP", "stream", "system", "stopSequences", "Prompt", "MAXTOKENS", "max_tokens",
        "prompt ", " maxTokens", "body2", "__proto__", "\u30D1\u30E9\u30E1\u30FC\u30BF", "a"
    })
    @DisplayName("`Given an unsupported member on a bare payload When parsed Then it is rejected naming the member (Property 6)`")
    void bareTopLevelUnsupportedMemberIsRejectedByName(String member) throws JsonProcessingException {
        String payload = payloadWith(member);

        assertRejectedNaming(member, parser.resolve(stream(payload)));
    }

    @ParameterizedTest(name = "Given member [{0}] in a proxy body When parsed Then it is rejected")
    @ValueSource(strings = {
        "headPadBytes", "tailPadBytes", "headPadEvents", "temperature", "modelId", "stream", "Prompt", "max_tokens"
    })
    @DisplayName("`Given an unsupported member in a proxy body When parsed Then it is rejected naming the member (Property 6)`")
    void unsupportedMemberInsideProxyEventBodyIsRejectedByName(String member) throws JsonProcessingException {
        ObjectNode event = MAPPER.createObjectNode();
        event.put("body", payloadWith(member));

        assertRejectedNaming(member, parser.resolve(stream(MAPPER.writeValueAsString(event))));
    }

    private static String payloadWith(String member) throws JsonProcessingException {
        // Valid supported members alongside: the extra member alone must cause the rejection.
        return MAPPER.writeValueAsString(Map.of("prompt", "hello", "maxTokens", 10, member, 1));
    }

    private static void assertRejectedNaming(String member, PromptResult result) {
        PromptResult.Rejected rejected = assertInstanceOf(PromptResult.Rejected.class, result);
        assertEquals(400, rejected.status());
        String shown = member.length() > MAX_NAME_LEN ? member.substring(0, MAX_NAME_LEN) : member;
        assertTrue(rejected.message().contains(shown), "message must name the member: " + rejected.message());
    }

    private static ByteArrayInputStream stream(String json) {
        return new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));
    }
}
