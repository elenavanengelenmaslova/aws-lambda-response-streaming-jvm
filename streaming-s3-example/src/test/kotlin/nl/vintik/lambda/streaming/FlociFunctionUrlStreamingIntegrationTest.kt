package nl.vintik.lambda.streaming

import com.amazonaws.services.lambda.runtime.Context
import io.mockk.mockk
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.stream.Stream
import kotlin.random.Random

/**
 * Floci S3 integration test proving the **Lambda Function URL** streaming path end to end for the
 * Kotlin example (Req 5.1, 5.2, 5.3, 5.4).
 *
 * This is the Function URL counterpart to [SubSixMbStreamingIntegrationTest] and
 * [FifteenMbStreamingIntegrationTest]: container lifecycle, the emulated S3 client, object cleanup,
 * and protocol parsing all come from [FlociS3IntegrationTestBase] — one container per class, only
 * object data cleaned between tests (Req 5.4). This class contributes the Function-URL fixtures and
 * the byte-identity assertions.
 *
 * ## What is exercised
 * The real [FunctionUrlResolver] → [S3Source] → [ResponseWriter] pipeline — the exact wiring
 * [S3FunctionUrlHandler] uses — driven with a Lambda payload format **2.0** Function URL event, so
 * the Function-URL event shape (`rawPath` + `requestContext.http`) is parsed and resolved against a
 * live S3 implementation rather than a mock. The event carries an `AWS_IAM`-shaped
 * `requestContext.authorizer.iam` section (Req 5.3): the resolver must tolerate and ignore it,
 * proving the handler is driven by the same event shape the IAM-authenticated Function URL produces.
 *
 * ## Why a locally wired handler rather than the production entry point
 * [S3FunctionUrlHandler] wires `::S3Source`, whose default `S3Client { }` resolves region and
 * credentials but **not** an endpoint, so it cannot be redirected at the emulated S3 without
 * changing production code (the same constraint [FlociS3IntegrationTestBase] documents for the API
 * Gateway path). This test therefore drives a [StreamHandler] wired with the production
 * [FunctionUrlResolver] and an emulator-pointed [S3Source] — identical collaborators to
 * [S3FunctionUrlHandler], differing only in the S3 endpoint.
 *
 * ## What it deliberately does not prove
 * Real IAM request signing and progressive (first-byte-early) delivery over the wire. No local
 * emulator implements the Function URL streaming invocation path or SigV4 verification, so those
 * are deferred to the signed post-deploy script against real AWS (Req 5.5, Req 9); here the handler
 * is fed an `AWS_IAM`-shaped event directly.
 */
class FlociFunctionUrlStreamingIntegrationTest : FlociS3IntegrationTestBase() {

    private val context: Context = mockk(relaxed = true)

    /**
     * The Function URL pipeline wired to the emulated S3: the production [FunctionUrlResolver] and
     * an emulator-pointed [S3Source], matching [S3FunctionUrlHandler]'s wiring except for the S3
     * endpoint. Driving this exercises the full parse-2.0-event -> validate -> head -> stream path.
     */
    private fun functionUrlHandler(): StreamHandler<FileRequest> = StreamHandler(
        requestResolver = ::FunctionUrlResolver,
        source = { S3Source(bucket = BUCKET, client = s3) },
    )

    private fun streamThroughFunctionUrl(key: String): ByteArray {
        val output = ByteArrayOutputStream()
        functionUrlHandler().handleRequest(
            ByteArrayInputStream(functionUrlEvent(key)),
            output,
            context,
        )
        return output.toByteArray()
    }

    // ---- Task 2.1: sub-6 MB -------------------------------------------------------------------

    @Test
    fun `Given a sub-6 MB S3 object When streamed through the Function URL handler Then the received body is byte-identical`() {
        // 5 MB of pseudo-random bytes: comfortably under the legacy 6 MB buffered limit, larger
        // than the 1 MB transfer buffer so multiple bounded-buffer chunks are exercised.
        val key = "fn-url-sub-six.bin"
        val payload = Random(42).nextBytes(5 * 1024 * 1024)
        upload(key, payload)

        val response = streamThroughFunctionUrl(key)

        // The AWS_IAM-shaped payload-2.0 event resolved to a valid name and committed 200.
        assertEquals(200, extractMetadata(response).statusCode, "a present object must commit 200")

        val received = extractBody(response)
        assertEquals(payload.size, received.size, "received body length must equal the uploaded object size")
        assertArrayEquals(payload, received, "received body must be byte-identical to the uploaded object")
    }

    // ---- Task 2.2: >= 15 MB -------------------------------------------------------------------

    @Test
    fun `Given a ~15 MB S3 object When streamed through the Function URL handler Then status is 200 and the body is byte-identical past the 6 MB limit`() {
        // ~15 MB of pseudo-random bytes: well over the legacy 6 MB buffered limit and many multiples
        // of the 1 MB transfer buffer, so the bounded-buffer copy loops repeatedly.
        val key = "fn-url-Test_Object"
        val payload = Random(15).nextBytes(15 * 1024 * 1024)
        assertTrue(payload.size > 6 * 1024 * 1024, "fixture must exceed the legacy 6 MB limit")
        upload(key, payload)

        val response = streamThroughFunctionUrl(key)

        assertEquals(200, extractMetadata(response).statusCode, "committed status must be 200 for the ~15 MB object")

        val received = extractBody(response)
        assertEquals(
            payload.size,
            received.size,
            "received body byte count must equal the stored object size (> 6 MB)",
        )
        assertArrayEquals(payload, received, "received body must be byte-identical to the uploaded object")
    }

    // ---- Property 3: byte-identity across sizes ------------------------------------------------

    /**
     * Property 3: Streaming output over the Function URL path is byte-identical to the source.
     *
     * For an object stored in S3 and requested through the Function URL event, the body delivered
     * after the metadata JSON and the 8 null-byte delimiter is byte-for-byte identical to the stored
     * object — across both a sub-6 MB case and a >= 15 MB case (past the legacy buffered limit).
     *
     * Validates: Requirements 5.1, 5.2, 5.3
     */
    @DisplayName(
        "Feature: function-url-streaming, Property 3: Function URL streamed body is byte-identical to the source",
    )
    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("sizeCases")
    fun `Given an S3 object of a given size When streamed through the Function URL path Then the body is byte-identical to the source`(
        @Suppress("UNUSED_PARAMETER") label: String,
        sizeBytes: Int,
        seed: Int,
    ) {
        val key = "fn-url-prop3-$sizeBytes.bin"
        val payload = Random(seed).nextBytes(sizeBytes)
        upload(key, payload)

        val response = streamThroughFunctionUrl(key)

        assertEquals(200, extractMetadata(response).statusCode, "a present object must commit 200 for size $sizeBytes")

        val received = extractBody(response)
        assertEquals(payload.size, received.size, "received body length must equal the stored object size ($sizeBytes)")
        assertArrayEquals(payload, received, "received body must be byte-identical to the source for size $sizeBytes")
    }

    companion object {
        @JvmStatic
        fun sizeCases(): Stream<Arguments> = Stream.of(
            // sub-6 MB: under the legacy buffered limit, multiple transfer-buffer chunks.
            Arguments.of("sub-6 MB (5 MB)", 5 * 1024 * 1024, 42),
            // >= 15 MB: well past the legacy 6 MB limit, many bounded-buffer loops.
            Arguments.of(">= 15 MB (15 MB)", 15 * 1024 * 1024, 15),
        )

        /**
         * Builds a representative Lambda payload format 2.0 Function URL event for [fileName],
         * including an `AWS_IAM`-shaped `requestContext.authorizer.iam` section (Req 5.3) that the
         * resolver must tolerate and ignore. Both `rawPath` and `requestContext.http.path` are set.
         */
        @JvmStatic
        fun functionUrlEvent(fileName: String): ByteArray {
            val path = "/$fileName"
            return buildJsonObject {
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
    }
}
