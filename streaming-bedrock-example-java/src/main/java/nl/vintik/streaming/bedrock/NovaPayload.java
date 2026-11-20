package nl.vintik.streaming.bedrock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Pure helpers for the Amazon Nova "messages-v1" wire format: building the request body and
 * extracting incremental text from streaming events. Stateless, so it is directly property-testable.
 */
final class NovaPayload {

    private static final Logger LOGGER = LoggerFactory.getLogger(NovaPayload.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final double TEMPERATURE = 0.7;

    private NovaPayload() {
    }

    /**
     * Builds the Nova request body for a single user prompt and token cap. Uses Jackson's tree model,
     * never string concatenation, so quotes, newlines and Unicode round-trip exactly.
     */
    static String requestBody(String prompt, int maxTokens) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("schemaVersion", "messages-v1");
        ObjectNode message = root.putArray("messages").addObject();
        message.put("role", "user");
        message.putArray("content").addObject().put("text", prompt);
        ObjectNode inference = root.putObject("inferenceConfig");
        inference.put("maxTokens", maxTokens);
        inference.put("temperature", TEMPERATURE);
        return root.toString();
    }

    /**
     * Returns the text of {@code contentBlockDelta.delta.text}, or {@code null} when the event carries
     * none (messageStart, contentBlockStop, metadata, malformed JSON, non-object root). Never throws.
     */
    static String textDelta(String json) {
        if (json == null) {
            return null;
        }
        try {
            JsonNode text = MAPPER.readTree(json).path("contentBlockDelta").path("delta").path("text");
            return text.isTextual() ? text.textValue() : null;
        } catch (Exception e) {
            // Only the exception type is logged: the message echoes the payload, which is model output.
            LOGGER.warn("Skipping unparseable Nova stream event ({})", e.getClass().getSimpleName());
            return null;
        }
    }
}
