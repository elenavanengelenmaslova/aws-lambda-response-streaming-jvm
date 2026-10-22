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
        assertEquals(DEFAULT_HEAD_PAD_BYTES, req.headPadBytes)
        assertEquals(DEFAULT_TAIL_PAD_BYTES, req.tailPadBytes)
        assertEquals(DEFAULT_MAX_TOKENS, req.maxTokens)
    }

    @Test
    fun `Given a proxy event body When resolved Then prompt and knobs are read from the nested body`() {
        val raw = """{"body":"{\"prompt\":\"hello\",\"headPadBytes\":2048,\"tailPadBytes\":256,\"maxTokens\":123}"}"""
        val result = resolve(raw)
        val resolved = assertInstanceOf(RequestResult.Resolved::class.java, result)
        val req = resolved.request as PromptRequest
        assertEquals("hello", req.prompt)
        assertEquals(2048, req.headPadBytes)
        assertEquals(256, req.tailPadBytes)
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
    fun `Given out-of-range knobs When resolved Then they are clamped to safe bounds`() {
        val raw = """{"prompt":"p","maxTokens":999999,"headPadBytes":99999999,"tailPadBytes":-5,"headPadEvents":1000}"""
        val result = resolve(raw)
        val resolved = assertInstanceOf(RequestResult.Resolved::class.java, result)
        val req = resolved.request as PromptRequest
        assertEquals(MAX_TOKENS_CEILING, req.maxTokens)
        assertEquals(MAX_PAD_BYTES, req.headPadBytes)
        assertEquals(0, req.tailPadBytes, "negative tail pad clamps to 0")
        assertEquals(100, req.headPadEvents, "headPadEvents clamps to 100")
    }

    @Test
    fun `Given malformed JSON When resolved Then defaults are applied without throwing`() {
        val result = resolve("not json at all {{{")
        val resolved = assertInstanceOf(RequestResult.Resolved::class.java, result)
        assertTrue((resolved.request as PromptRequest).prompt.isNotBlank())
    }
}
