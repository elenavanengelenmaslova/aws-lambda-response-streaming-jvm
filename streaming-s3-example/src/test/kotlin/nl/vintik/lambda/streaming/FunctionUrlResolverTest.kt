package nl.vintik.lambda.streaming

import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream

/**
 * Unit tests for [FunctionUrlResolver].
 *
 * Covers the three top-level resolution paths against a representative Lambda payload
 * format 2.0 Function URL event (`rawPath` + `requestContext.http`, including an
 * `AWS_IAM`-shaped `requestContext.authorizer.iam` section that must be tolerated):
 *  - Empty / malformed input → [RequestResult.Error] 400 (parse failure).
 *  - Well-formed payload-2.0 event with a rejected file name → [RequestResult.Error] 400.
 *  - Well-formed payload-2.0 event with an accepted file name → [RequestResult.Resolved]
 *    carrying the extracted [FileRequest].
 *
 * Assertions are on typed results (Resolved vs. Error + status code), not on whole-response
 * string equality. Exhaustive validation-rule coverage lives in [FileNameValidatorTest];
 * property-level parity and the no-S3 guarantee are covered by tasks 1.4 / 1.5.
 */
class FunctionUrlResolverTest {

    private val resolver = FunctionUrlResolver()

    // --- Parse error → 400 ---

    @Test
    fun `Given empty input When resolved Then returns Error 400`() {
        val result = resolver.resolve(ByteArrayInputStream(ByteArray(0)))

        val error = assertInstanceOf(RequestResult.Error::class.java, result)
        assertEquals(400, error.statusCode)
    }

    @Test
    fun `Given malformed JSON When resolved Then returns Error 400`() {
        val result = resolver.resolve(ByteArrayInputStream("not-json".toByteArray()))

        val error = assertInstanceOf(RequestResult.Error::class.java, result)
        assertEquals(400, error.statusCode)
    }

    // --- Validation rejection → 400 ---

    @Test
    fun `Given parent-directory file name When resolved Then returns Error 400`() {
        val result = resolver.resolve(ByteArrayInputStream(functionUrlEvent("/..")))

        val error = assertInstanceOf(RequestResult.Error::class.java, result)
        assertEquals(400, error.statusCode)
    }

    @Test
    fun `Given path-separator-only path When resolved Then returns Error 400`() {
        // A trailing slash leaves an empty last segment -> MISSING -> 400, and no S3 call.
        val result = resolver.resolve(ByteArrayInputStream(functionUrlEvent("/dir/")))

        val error = assertInstanceOf(RequestResult.Error::class.java, result)
        assertEquals(400, error.statusCode)
    }

    @Test
    fun `Given file name with illegal character When resolved Then returns Error 400`() {
        val result = resolver.resolve(ByteArrayInputStream(functionUrlEvent("/re port.bin")))

        val error = assertInstanceOf(RequestResult.Error::class.java, result)
        assertEquals(400, error.statusCode)
    }

    @Test
    fun `Given file name that is too long When resolved Then returns Error 400`() {
        val result = resolver.resolve(ByteArrayInputStream(functionUrlEvent("/" + "a".repeat(1025))))

        val error = assertInstanceOf(RequestResult.Error::class.java, result)
        assertEquals(400, error.statusCode)
    }

    // --- Valid name → Resolved ---

    @Test
    fun `Given valid file name When resolved Then returns Resolved with FileRequest`() {
        val result = resolver.resolve(ByteArrayInputStream(functionUrlEvent("/report.bin")))

        val resolved = assertInstanceOf(RequestResult.Resolved::class.java, result)
        assertEquals(FileRequest("report.bin"), resolved.request)
    }

    @Test
    fun `Given nested path When resolved Then extracts last segment as file name`() {
        val result = resolver.resolve(ByteArrayInputStream(functionUrlEvent("/some/nested/report.bin")))

        val resolved = assertInstanceOf(RequestResult.Resolved::class.java, result)
        assertEquals(FileRequest("report.bin"), resolved.request)
    }

    /**
     * Builds a representative Lambda payload format 2.0 Function URL event with both
     * `rawPath` and `requestContext.http.path` set to [path], plus an `AWS_IAM`-shaped
     * `requestContext.authorizer.iam` section the resolver must tolerate and ignore.
     */
    private fun functionUrlEvent(path: String): ByteArray =
        buildJsonObject {
            put("version", "2.0")
            put("routeKey", "\$default")
            put("rawPath", path)
            put("rawQueryString", "")
            putJsonObject("headers") {
                put("host", "abc123.lambda-url.eu-west-1.on.aws")
                put("x-forwarded-proto", "https")
            }
            putJsonObject("requestContext") {
                put("accountId", "123456789012")
                put("apiId", "abc123")
                put("domainName", "abc123.lambda-url.eu-west-1.on.aws")
                putJsonObject("http") {
                    put("method", "GET")
                    put("path", path)
                    put("protocol", "HTTP/1.1")
                    put("sourceIp", "203.0.113.1")
                }
                putJsonObject("authorizer") {
                    putJsonObject("iam") {
                        put("accessKey", "AKIAEXAMPLE")
                        put("accountId", "123456789012")
                        put("callerId", "AROAEXAMPLE:session")
                        put("userArn", "arn:aws:sts::123456789012:assumed-role/example/session")
                    }
                }
                put("requestId", "id")
                put("routeKey", "\$default")
                put("stage", "\$default")
                put("time", "01/Jan/2025:00:00:00 +0000")
                put("timeEpoch", 1735689600000L)
            }
            putJsonArray("cookies") { add("session=abc") }
            put("isBase64Encoded", false)
        }.toString().toByteArray(Charsets.UTF_8)
}
