package nl.vintik.streaming.bedrock;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Unit tests for {@link NovaPayload#textDelta(String)}. */
class NovaPayloadTextDeltaTest {

    @Test
    @DisplayName("`Given a content block delta with text When textDelta is read Then the text is returned`")
    void contentBlockDeltaWithTextReturnsText() {
        String json = "{\"contentBlockDelta\":{\"delta\":{\"text\":\"Hello\"},\"contentBlockIndex\":0}}";
        assertEquals("Hello", NovaPayload.textDelta(json));
    }

    @Test
    @DisplayName("`Given text with escapes and Unicode When textDelta is read Then it is decoded`")
    void textWithEscapesAndUnicodeIsDecoded() {
        String json = "{\"contentBlockDelta\":{\"delta\":{\"text\":\"a\\n\\\"b\\\" \\u00e9 \\ud83d\\ude00\"}}}";
        assertEquals("a\n\"b\" \u00e9 \uD83D\uDE00", NovaPayload.textDelta(json));
    }

    @Test
    @DisplayName("`Given empty text When textDelta is read Then an empty string is returned`")
    void emptyTextReturnsEmptyString() {
        String json = "{\"contentBlockDelta\":{\"delta\":{\"text\":\"\"}}}";
        assertEquals("", NovaPayload.textDelta(json));
    }

    @Test
    @DisplayName("`Given a delta without text When textDelta is read Then null is returned`")
    void deltaWithoutTextReturnsNull() {
        assertNull(NovaPayload.textDelta("{\"contentBlockDelta\":{\"delta\":{}}}"));
    }

    @Test
    @DisplayName("`Given non textual text When textDelta is read Then null is returned`")
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
    @DisplayName("`Given an event without text When textDelta is read Then null is returned`")
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
    @DisplayName("`Given malformed or non object input When textDelta is read Then null is returned without throwing`")
    void malformedOrNonObjectInputReturnsNullWithoutThrowing(String json) {
        assertNull(assertDoesNotThrow(() -> NovaPayload.textDelta(json)));
    }

    @ParameterizedTest
    @NullSource
    @DisplayName("`Given a null input When textDelta is read Then null is returned`")
    void nullInputReturnsNull(String json) {
        assertNull(assertDoesNotThrow(() -> NovaPayload.textDelta(json)));
    }
}
