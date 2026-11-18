package nl.vintik.lambda.bedrock

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream

class PromptResolverTest {

    private val resolver = PromptResolver()

    private fun resolve(raw: String): RequestResult<PromptRequest> =
        resolver.resolve(ByteArrayInputStream(raw.toByteArray()))

    @Test
    fun `Given an empty body When resolved Then defaults are applied`() {
        val result = resolve("")
        val resolved = assertInstanceOf(RequestResult.Resolved::class.java, result)
        val req = resolved.request as PromptRequest
        assertEquals(DEFAULT_PROMPT, req.prompt)
        assertEquals(DEFAULT_MAX_TOKENS, req.maxTokens)
    }

    @Test
    fun `Given a proxy event body When resolved Then prompt and maxTokens are read from the nested body`() {
        val raw = """{"body":"{\"prompt\":\"hello\",\"maxTokens\":123}"}"""
        val result = resolve(raw)
        val resolved = assertInstanceOf(RequestResult.Resolved::class.java, result)
        val req = resolved.request as PromptRequest
        assertEquals("hello", req.prompt)
        assertEquals(123, req.maxTokens)
    }

    @Test
    fun `Given a raw JSON body When resolved Then it is read directly`() {
        val result = resolve("""{"prompt":"direct","maxTokens":200}""")
        val resolved = assertInstanceOf(RequestResult.Resolved::class.java, result)
        assertEquals("direct", (resolved.request as PromptRequest).prompt)
    }

    @Test
    fun `Given an over-long prompt When resolved Then it is rejected with 400 and no model call`() {
        val longPrompt = "x".repeat(MAX_PROMPT_LEN + 1)
        val result = resolve("""{"prompt":"$longPrompt"}""")
        val error = assertInstanceOf(RequestResult.Error::class.java, result)
        assertEquals(400, error.statusCode)
    }

    @Test
    fun `Given an out-of-range maxTokens When resolved Then it is clamped to the ceiling`() {
        val result = resolve("""{"prompt":"p","maxTokens":999999}""")
        val resolved = assertInstanceOf(RequestResult.Resolved::class.java, result)
        assertEquals(MAX_TOKENS_CEILING, (resolved.request as PromptRequest).maxTokens)
    }

    @Test
    fun `Given a leftover padding knob from the old API When resolved Then it is ignored rather than failing`() {
        val raw = """{"prompt":"p","headPadBytes":102400,"tailPadBytes":10240,"headPadEvents":3}"""
        val result = resolve(raw)
        val resolved = assertInstanceOf(RequestResult.Resolved::class.java, result)
        assertEquals("p", (resolved.request as PromptRequest).prompt)
    }

    @Test
    fun `Given a maxTokens below the floor When resolved Then it is clamped to one`() {
        val result = resolve("""{"prompt":"p","maxTokens":0}""")
        val resolved = assertInstanceOf(RequestResult.Resolved::class.java, result)
        assertEquals(1, (resolved.request as PromptRequest).maxTokens)
    }

    @Test
    fun `Given malformed JSON When resolved Then defaults are applied without throwing`() {
        val result = resolve("not json at all {{{")
        val resolved = assertInstanceOf(RequestResult.Resolved::class.java, result)
        assertTrue((resolved.request as PromptRequest).prompt.isNotBlank())
    }
}
