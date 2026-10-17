package nl.vintik.lambda.streaming

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.io.ByteArrayInputStream
import java.util.stream.Stream

/**
 * Property 1: Function URL path extraction yields the same file name as the proxy path.
 *
 * For all file names `n` in the valid set, a Lambda payload format 2.0 event whose `rawPath`
 * is `"/" + n` (and whose equivalent `requestContext.http.path` is `"/" + n`) resolves, via
 * [FunctionUrlResolver], to [RequestResult.Resolved] carrying `FileRequest(n)` — the SAME
 * [FileRequest] the API Gateway [FileKeyResolver] produces for `pathParameters.proxy = n`.
 *
 * This proves the Function URL path extraction matches the proxy path resolution for every
 * equivalent request: the two invocation layers deliver different event shapes, but the file
 * name lifted out of each — and therefore the resolved domain request — is identical. The
 * resolved values are compared directly (`Resolved` vs `Resolved`), not via response-string
 * equality.
 *
 * The valid names span the allow-list (`A–Z`, `a–z`, `0–9`, `-`, `_`, `.`) and the boundary
 * lengths (single character up to the 1024-character maximum).
 *
 * Validates: Requirements 2.1, 2.3, 4.1
 */
@DisplayName(
    "Feature: function-url-streaming, Property 1: Function URL path extraction matches proxy path resolution",
)
class FunctionUrlPathExtractionParityPropertyTest {

    private val functionUrlResolver = FunctionUrlResolver()
    private val proxyResolver = FileKeyResolver()

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("validNames")
    fun `Given a valid name When resolved from a Function URL event and a proxy event Then both yield the same FileRequest`(
        @Suppress("UNUSED_PARAMETER") label: String,
        name: String,
    ) {
        val functionUrlResult = functionUrlResolver.resolve(ByteArrayInputStream(functionUrlEvent(name)))
        val proxyResult = proxyResolver.resolve(ByteArrayInputStream(proxyEvent(name)))

        val functionUrlResolved = assertInstanceOf(
            RequestResult.Resolved::class.java,
            functionUrlResult,
            "Function URL event for a valid name must resolve to Resolved for: <$name>",
        )
        val proxyResolved = assertInstanceOf(
            RequestResult.Resolved::class.java,
            proxyResult,
            "proxy event for a valid name must resolve to Resolved for: <$name>",
        )

        // Parity: the Function URL path extraction produces the same FileRequest as the proxy path.
        assertEquals(
            FileRequest(name),
            functionUrlResolved.request,
            "Function URL resolver must produce FileRequest(<$name>)",
        )
        assertEquals(
            proxyResolved.request,
            functionUrlResolved.request,
            "Function URL and proxy resolvers must produce the identical FileRequest for: <$name>",
        )
    }

    companion object {

        /** Builds a Lambda payload-2.0 Function URL event whose path is `"/" + fileName`. */
        private fun functionUrlEvent(fileName: String): ByteArray {
            val path = "/$fileName"
            return buildJsonObject {
                put("version", "2.0")
                put("rawPath", path)
                putJsonObject("requestContext") {
                    putJsonObject("http") {
                        put("method", "GET")
                        put("path", path)
                    }
                }
            }.toString().toByteArray(Charsets.UTF_8)
        }

        /** Builds an API Gateway `/{proxy+}` event whose `pathParameters.proxy` is [fileName]. */
        private fun proxyEvent(fileName: String): ByteArray =
            buildJsonObject {
                putJsonObject("pathParameters") {
                    put("proxy", fileName)
                }
            }.toString().toByteArray(Charsets.UTF_8)

        @JvmStatic
        fun validNames(): Stream<Arguments> = Stream.of(
            // --- single-segment allow-list coverage ---
            Arguments.of("simple name", "report.pdf"),
            Arguments.of("hyphen underscore version", "my-file_v2.txt"),
            Arguments.of("single char", "a"),
            Arguments.of("single digit", "7"),
            Arguments.of("multi extension", "data.tar.gz"),
            Arguments.of("leading underscore", "_hidden"),
            Arguments.of("leading hyphen", "-dash-start"),
            Arguments.of("mixed allow-list", "A.B-C_D.9"),
            Arguments.of("all uppercase", "README"),
            Arguments.of("all digits", "1234567890"),
            Arguments.of("dot only", "."),
            Arguments.of("trailing dot", "file."),
            Arguments.of("long dotted segments", "a.b.c.d.e.f.g"),
            Arguments.of("underscores and digits", "snap_start_01"),
            Arguments.of("uuid-like", "550e8400-e29b-41d4-a716-446655440000"),

            // --- boundary lengths ---
            Arguments.of("two char boundary", "ab"),
            Arguments.of("1023-char just under max", "a".repeat(1023)),
            Arguments.of("1024-char at max boundary", "a".repeat(1024)),
        )
    }
}
