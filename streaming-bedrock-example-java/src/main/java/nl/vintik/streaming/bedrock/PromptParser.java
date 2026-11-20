package nl.vintik.streaming.bedrock;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Turns the invocation event into a {@link PromptResult}.
 *
 * <p>A document with a {@code body} member is an API Gateway proxy event and its {@code body}
 * string is the payload; a document without one is the payload itself (unit tests, local
 * invocation). Only {@code prompt} and {@code maxTokens} are accepted. Only exception types are
 * logged, never the payload.
 */
final class PromptParser {

    private static final Logger LOG = LoggerFactory.getLogger(PromptParser.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> SUPPORTED = Set.of("prompt", "maxTokens");
    private static final int MAX_NAME_LEN = 64;

    private static final String INVALID_JSON = "The request body is not valid JSON.";
    private static final String PROMPT_NOT_STRING = "The prompt must be a string.";
    private static final String PROMPT_TOO_LONG = "The prompt is too long.";

    /** Reads the event from {@code input} and resolves it to a request or a 400 rejection. */
    PromptResult resolve(InputStream input) {
        JsonNode event;
        try {
            event = MAPPER.readTree(input);
        } catch (IOException | RuntimeException e) {
            return invalidJson(e);
        }
        // An empty stream yields a missing node: nothing to parse, use defaults.
        if (event == null || event.isMissingNode() || event.isNull()) {
            return defaults();
        }
        if (!event.isObject()) {
            return new PromptResult.Rejected(400, INVALID_JSON);
        }

        JsonNode payload = event;
        if (event.has("body")) {
            JsonNode body = event.get("body");
            if (body.isNull()) {
                return defaults();
            }
            if (!body.isTextual()) {
                return new PromptResult.Rejected(400, INVALID_JSON);
            }
            String text = body.textValue();
            if (text.isBlank()) {
                return defaults();
            }
            try {
                payload = MAPPER.readTree(text);
            } catch (JacksonException e) {
                return invalidJson(e);
            }
            if (payload == null || !payload.isObject()) {
                return new PromptResult.Rejected(400, INVALID_JSON);
            }
        }
        return resolvePayload(payload);
    }

    private static PromptResult resolvePayload(JsonNode payload) {
        for (Iterator<String> names = payload.fieldNames(); names.hasNext(); ) {
            String name = names.next();
            if (!SUPPORTED.contains(name)) {
                String shown = name.length() > MAX_NAME_LEN ? name.substring(0, MAX_NAME_LEN) : name;
                return new PromptResult.Rejected(400, "Unsupported parameter: " + shown);
            }
        }

        String prompt = PromptLimits.DEFAULT_PROMPT;
        JsonNode promptNode = payload.get("prompt");
        if (promptNode != null && !promptNode.isNull()) {
            if (!promptNode.isTextual()) {
                return new PromptResult.Rejected(400, PROMPT_NOT_STRING);
            }
            String text = promptNode.textValue();
            if (text.length() > PromptLimits.MAX_PROMPT_LEN) {
                return new PromptResult.Rejected(400, PROMPT_TOO_LONG);
            }
            if (!text.isBlank()) {
                prompt = text;
            }
        }
        return new PromptResult.Resolved(new PromptRequest(prompt, clampTokens(payload.get("maxTokens"))));
    }

    private static int clampTokens(JsonNode node) {
        if (node == null || !node.isNumber()) {
            return PromptLimits.DEFAULT_MAX_TOKENS;
        }
        if (node.isIntegralNumber() && !node.canConvertToLong()) {
            // Beyond long range (BigInteger): only the sign matters once clamped.
            return node.bigIntegerValue().signum() < 0 ? 1 : PromptLimits.MAX_TOKENS_CEILING;
        }
        double value = node.isIntegralNumber() ? node.longValue() : node.doubleValue();
        if (Double.isNaN(value)) {
            return PromptLimits.DEFAULT_MAX_TOKENS;
        }
        return (int) Math.max(1, Math.min(PromptLimits.MAX_TOKENS_CEILING, value));
    }

    private static PromptResult defaults() {
        return new PromptResult.Resolved(
                new PromptRequest(PromptLimits.DEFAULT_PROMPT, PromptLimits.DEFAULT_MAX_TOKENS));
    }

    private static PromptResult invalidJson(Exception e) {
        LOG.warn("Request rejected as invalid JSON: {}", e.getClass().getSimpleName());
        return new PromptResult.Rejected(400, INVALID_JSON);
    }
}
