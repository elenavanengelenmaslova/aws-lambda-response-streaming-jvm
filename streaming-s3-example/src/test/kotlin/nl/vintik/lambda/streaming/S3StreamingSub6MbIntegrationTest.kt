package nl.vintik.lambda.streaming

import com.amazonaws.services.lambda.runtime.Context
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import kotlin.random.Random

/**
 * End-to-end Floci integration test for the sub-6 MB streaming path, asserting the **metadata
 * prelude** as well as the body (Req 5.6).
 *
 * Where [SubSixMbStreamingIntegrationTest] checks the body alone, this test also decodes segment 1
 * of the protocol and asserts the committed status and the declared `Content-Length`, so a
 * regression in the prelude cannot pass unnoticed just because the bytes still arrive.
 *
 * Container lifecycle, the S3 client, object cleanup, and protocol parsing come from
 * [FlociS3IntegrationTestBase].
 */
class S3StreamingSub6MbIntegrationTest : FlociS3IntegrationTestBase() {

    @Test
    fun `Given a sub-6 MB S3 object When streamed end-to-end through the handler Then the body is byte-identical`() {
        val key = "sub6.bin"
        // 5 MB of random bytes: comfortably under the 6 MB legacy buffered limit, spanning
        // several 1 MB bounded-buffer chunks plus a partial final chunk.
        val payload = Random(42).nextBytes(5 * 1024 * 1024)
        upload(key, payload)

        val output = ByteArrayOutputStream()
        handlerForBucket().handleRequest(
            proxyEvent(key).byteInputStream(),
            output,
            mockk<Context>(relaxed = true),
        )

        val responseBytes = output.toByteArray()

        // Segment 1: metadata JSON -> status 200 with the declared content length.
        val metadata = extractMetadata(responseBytes)
        assertEquals(200, metadata.statusCode)
        assertEquals(payload.size.toString(), metadata.headers["Content-Length"])

        // Segment 3: body bytes after the 8 null-byte delimiter -> byte-identical to the upload.
        val body = extractBody(responseBytes)
        assertEquals(payload.size, body.size, "received body length must equal the uploaded size")
        assertArrayEquals(payload, body, "streamed body must be byte-identical to the uploaded object")
    }
}
