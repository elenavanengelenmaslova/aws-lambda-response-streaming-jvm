package nl.vintik.lambda.bedrock

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NovaHelpersTest {

    @Test
    fun `Given a prompt and token cap When building the Nova body Then it is valid messages-v1 JSON`() {
        val body = novaRequestBody("Hello \"world\"", 321)
        assertTrue(body.contains("\"schemaVersion\":\"messages-v1\""))
        assertTrue(body.contains("\"maxTokens\":321"))
        // The prompt is JSON-escaped, so the embedded quotes are preserved safely.
        assertTrue(body.contains("Hello \\\"world\\\""))
    }

    @Test
    fun `Given a contentBlockDelta event When extracting Then the text delta is returned`() {
        val text = extractNovaTextDelta("""{"contentBlockDelta":{"delta":{"text":"cloud "}}}""")
        assertEquals("cloud ", text)
    }

    @Test
    fun `Given a non-text event When extracting Then null is returned`() {
        assertNull(extractNovaTextDelta("""{"messageStart":{"role":"assistant"}}"""))
        assertNull(extractNovaTextDelta("""{"contentBlockStop":{"contentBlockIndex":0}}"""))
        assertNull(extractNovaTextDelta("not json"))
    }
}
