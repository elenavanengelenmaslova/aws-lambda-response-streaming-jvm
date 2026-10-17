package nl.vintik.lambda.streaming

import com.amazonaws.services.lambda.runtime.Context
import io.mockk.Called
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.serialization.json.Json
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
import java.io.ByteArrayOutputStream
import java.util.stream.Stream

/**
 * Property 2: Function URL resolver rejects exactly the unsafe set and issues no S3 request.
 *
 * For every raw payload-2.0 request body `s`, [FunctionUrlResolver] returns
 * [RequestResult.Error] with HTTP status 400 **if and only if** the body cannot be decoded
 * **or** the file name extracted from its request path (`rawPath`, falling back to
 * `requestContext.http.path`, with the proxy semantics `trimStart('/').substringAfterLast('/')`)
 * is rejected by the shared [FileNameValidator]. The biconditional is checked against the
 * **shared** validator as the reference predicate, exactly as the design specifies.
 *
 * Furthermore, for every rejected input, driving the full [StreamHandler] with that body issues
 * ZERO S3 interactions (`coVerify { s3Source wasNot Called }` against a relaxed [S3Source] mock)
 * and the committed HTTP status is 400 — the no-S3-on-reject guarantee of Req 2.4.
 *
 * The input space mixes: well-formed events with valid names, well-formed events with adversarial
 * paths (`..`, `/`, `\`, illegal chars, over-length, empty/absent path), and malformed/empty JSON.
 *
 * Validates: Requirements 2.2, 2.4, 4.1, 4.3
 */
@DisplayName(
    "Feature: function-url-streaming, Property 2: Function URL resolver rejects unsafe names and issues no S3 request",
)
class FunctionUrlRejectionZeroS3PropertyTest {

    private val validator = FileNameValidator()
    private val resolver = FunctionUrlResolver(validator = validator)

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("candidates")
    fun `Given a payload-2_0 body When resolved Then rejected iff undecodable-or-unsafe and every rejection issues no S3 request and a 400`(
        @Suppress("UNUSED_PARAMETER") label: String,
        decodable: Boolean,
        body: ByteArray,
    ) {
        // Reference predicate: a rejection is expected when the body cannot be decoded, or the
        // name extracted with the resolver's path semantics is rejected by the SHARED validator.
        val expectedRejected = if (!decodable) {
            true
        } else {
            val extracted = extractFileName(body)
            validator.validate(extracted) is ValidationResult.Invalid
        }

        val result = resolver.resolve(ByteArrayInputStream(body))
        val actualRejected = result is RequestResult.Error

        // Biconditional: resolver rejects (400) exactly when undecodable or unsafe.
        assertEquals(
            expectedRejected,
            actualRejected,
            "resolver reject/accept must match the undecodable-or-shared-validator reference for: <$label>",
        )

        if (expectedRejected) {
            // Every rejection is a 400 at the resolver boundary.
            val error = assertInstanceOf(RequestResult.Error::class.java, result)
            assertEquals(400, error.statusCode, "every rejection must be HTTP 400")

            // Drive the full handler for every rejected body and prove zero S3 access + committed 400.
            val s3Source = mockk<StreamSource<FileRequest>>(relaxed = true)
            val handler = StreamHandler(
                requestResolver = { resolver },
                source = { s3Source },
                responseWriter = ::ResponseWriter,
            )
            val output = ByteArrayOutputStream()

            handler.handleRequest(
                ByteArrayInputStream(body),
                output,
                mockk<Context>(relaxed = true),
            )

            // Req 2.4: no S3 request is issued for a rejected body.
            coVerify { s3Source wasNot Called }
            // The committed status for a rejected body is 400.
            assertEquals(400, statusOf(output.toByteArray()), "rejected bodies must commit HTTP 400")
        } else {
            // Sanity: an accepted body resolves to the extracted, validated file name.
            assertInstanceOf(RequestResult.Resolved::class.java, result)
            val request = (result as RequestResult.Resolved<FileRequest>).request
            assertEquals(extractFileName(body), request.fileName)
        }
    }

    companion object {
        private const val MAX_LENGTH = 1024

        /**
         * Reproduces the resolver's extraction semantics on a decodable body so the test can
         * feed the SHARED validator the exact name the resolver would validate.
         */
        @JvmStatic
        fun extractFileName(body: ByteArray): String {
            val event = LenientJson.decodeFromString<ProbeEvent>(body.decodeToString())
            val path = (event.rawPath ?: event.requestContext?.http?.path).orEmpty()
            return path.trimStart('/').substringAfterLast('/')
        }

        /** A payload-2.0 event body carrying [rawPath]. */
        private fun eventWithRawPath(rawPath: String): ByteArray =
            buildJsonObject {
                put("version", "2.0")
                put("rawPath", rawPath)
                putJsonObject("requestContext") {
                    putJsonObject("http") {
                        put("method", "GET")
                        put("path", rawPath)
                    }
                }
            }.toString().toByteArray(Charsets.UTF_8)

        /** A payload-2.0 event body with no path fields at all (absent `rawPath` and `http.path`). */
        private fun eventWithNoPath(): ByteArray =
            buildJsonObject {
                put("version", "2.0")
                putJsonObject("requestContext") {
                    putJsonObject("http") { put("method", "GET") }
                }
            }.toString().toByteArray(Charsets.UTF_8)

        /**
         * Extracts the committed HTTP status from the streaming response bytes by decoding the
         * metadata JSON prefix that precedes the 8 null-byte delimiter (segment 1 of the protocol).
         */
        private fun statusOf(bytes: ByteArray): Int {
            val delimiterStart = indexOfDelimiter(bytes)
            require(delimiterStart >= 0) { "response is missing the 8 null-byte delimiter" }
            val metadataJson = bytes.copyOfRange(0, delimiterStart).decodeToString()
            return Json.decodeFromString<ResponseMetadata>(metadataJson).statusCode
        }

        private const val DELIMITER_LEN = 8

        /** Index of the first run of [DELIMITER_LEN] consecutive zero bytes, or -1 if absent. */
        private fun indexOfDelimiter(bytes: ByteArray): Int {
            var run = 0
            for (i in bytes.indices) {
                if (bytes[i].toInt() == 0) {
                    run++
                    if (run == DELIMITER_LEN) return i - DELIMITER_LEN + 1
                } else {
                    run = 0
                }
            }
            return -1
        }

        @JvmStatic
        @Suppress("LongMethod")
        fun candidates(): Stream<Arguments> = Stream.of(
            // --- Accepted: well-formed events with safe names (via rawPath "/" + name) ---
            Arguments.of("accept simple name", true, eventWithRawPath("/report.pdf")),
            Arguments.of("accept hyphen-underscore-version", true, eventWithRawPath("/my-file_v2.txt")),
            Arguments.of("accept single char", true, eventWithRawPath("/a")),
            Arguments.of("accept multi-extension", true, eventWithRawPath("/data.tar.gz")),
            Arguments.of("accept last-segment of nested path", true, eventWithRawPath("/some/prefix/image.png")),
            Arguments.of("accept mixed allow-list", true, eventWithRawPath("/A.B-C_D.9")),

            // --- Rejected: well-formed events, adversarial / unsafe extracted names ---
            Arguments.of("reject bare parent dir", true, eventWithRawPath("/..")),
            Arguments.of("reject embedded double period", true, eventWithRawPath("/file..txt")),
            Arguments.of("reject empty last segment (trailing slash)", true, eventWithRawPath("/dir/")),
            Arguments.of("reject absent path -> empty name", true, eventWithNoPath()),
            Arguments.of("reject root path -> empty name", true, eventWithRawPath("/")),
            Arguments.of("reject embedded space", true, eventWithRawPath("/file name.txt")),
            Arguments.of("reject unicode accent", true, eventWithRawPath("/caf\u00e9.txt")),
            Arguments.of("reject percent sign", true, eventWithRawPath("/100%")),
            Arguments.of("reject semicolon", true, eventWithRawPath("/semi;colon")),
            Arguments.of("reject non-drive colon", true, eventWithRawPath("/ab:cd")),
            Arguments.of("reject over-length last segment", true, eventWithRawPath("/" + "a".repeat(1025))),

            // --- Rejected: malformed / empty JSON bodies (undecodable) ---
            Arguments.of("reject empty body", false, ByteArray(0)),
            Arguments.of("reject non-json body", false, "not-json".toByteArray(Charsets.UTF_8)),
            Arguments.of("reject truncated json", false, "{\"rawPath\":".toByteArray(Charsets.UTF_8)),
        )
    }
}

/**
 * Local decode projection mirroring the resolver's own private event model, used only by the
 * test's reference extractor so it feeds the shared validator the same name the resolver would.
 */
@kotlinx.serialization.Serializable
private data class ProbeEvent(
    val rawPath: String? = null,
    val requestContext: ProbeRequestContext? = null,
)

@kotlinx.serialization.Serializable
private data class ProbeRequestContext(val http: ProbeHttp? = null)

@kotlinx.serialization.Serializable
private data class ProbeHttp(val path: String? = null)
