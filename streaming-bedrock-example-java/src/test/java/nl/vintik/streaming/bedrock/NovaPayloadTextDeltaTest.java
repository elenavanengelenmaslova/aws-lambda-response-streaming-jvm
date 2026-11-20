package nl.vintik.streaming.bedrock;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Unit tests for {@link NovaPayload#textDelta(String)}. */
class NovaPayloadTextDeltaTest {

    @Test
    void contentBlockDeltaWithTextReturnsText() {
        String json = "{\"contentBlockDelta\":{\"delta\":{\"text\":\"Hello\"},\"contentBlockIndex\":0}}";
        assertEquals("Hello", NovaPayload.textDelta(json));
    }

    @Test
    void textWithEscapesAndUnicodeIsDecoded() {
        String json = "{\"contentBlockDelta\":{\"delta\":{\"text\":\"a\\n\\\"b\\\" \\u00e9 \\ud83d\\ude00\"}}}";
        assertEquals("a\n\"b\" \u00e9 \uD83D\uDE00", NovaPayload.textDelta(json));
    }

    @Test
    void emptyTextReturnsEmptyString() {
        String json = "{\"contentBlockDelta\":{\"delta\":{\"text\":\"\"}}}";
        assertEquals("", NovaPayload.textDelta(json));
    }

    @Test
    void deltaWithoutTextReturnsNull() {
        assertNull(NovaPayload.textDelta("{\"contentBlockDelta\":{\"delta\":{}}}"));
    }

    @Test
    void nonTextualTextReturnsNull() {
        assertNull(NovaPayload.textDelta("{\"contentBlockDelta\":{\"delta\":{\"text\":42}}}"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{\"messageStart\":{\"role\":\"assistant\"}}",
        "{\"contentBlockStop\":{\"contentBlockIndex\":0}}",
        "{\"metadata\":{\"usage\":{\"inputTokens\":3,\"outputTokens\":5}}}",
        "{}",
    })
    void eventsWithoutTextReturnNull(String json) {
        assertNull(assertDoesNotThrow(() -> NovaPayload.textDelta(json)));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{not json",
        "",
        "   ",
        "{\"contentBlockDelta\":",
        "[1,2,3]",
        "\"just a string\"",
        "42",
        "true",
        "null",
    })
    void malformedOrNonObjectInputReturnsNullWithoutThrowing(String json) {
        assertNull(assertDoesNotThrow(() -> NovaPayload.textDelta(json)));
    }

    @ParameterizedTest
    @NullSource
    void nullInputReturnsNull(String json) {
        assertNull(assertDoesNotThrow(() -> NovaPayload.textDelta(json)));
    }
}
