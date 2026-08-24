package nl.vintik.lambda.streaming

import com.amazonaws.services.lambda.runtime.Context
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.random.Random

/**
 * Floci S3 integration test proving the **~15 MB `Test_Object`** streaming path end to end —
 * delivery of a payload well past the legacy 6 MB buffered Lambda limit (Req 6.1, 6.2).
 *
 * Mirrors [SubSixMbStreamingIntegrationTest] but with a fixture many multiples of the 1 MB transfer
 * buffer, so the bounded-buffer copy loops repeatedly. From the protocol response it:
 *  - decodes the metadata JSON written before the 8 null-byte delimiter and asserts the committed
 *    status is `200` (Req 6.1);
 *  - asserts the received body byte count equals the stored object size (~15 MB, > 6 MB);
 *  - asserts the body is byte-identical to the uploaded object (Req 6.2).
 *
 * Container lifecycle, the S3 client, object cleanup, and protocol parsing come from
 * [FlociS3IntegrationTestBase].
 */
class FifteenMbStreamingIntegrationTest : FlociS3IntegrationTestBase() {

    private val context: Context = mockk(relaxed = true)

    @Test
    fun `Given a ~15 MB S3 object When streamed through the handler Then status is 200 and the received body is byte-identical past the 6 MB limit`() {
        // ~15 MB of pseudo-random bytes: well over the legacy 6 MB buffered limit and many
        // multiples of the 1 MB transfer buffer, so the bounded-buffer copy loops repeatedly.
        val key = "Test_Object"
        val payload = Random(15).nextBytes(15 * 1024 * 1024)
        assertTrue(payload.size > 6 * 1024 * 1024, "fixture must exceed the legacy 6 MB limit")
        upload(key, payload)

        val output = ByteArrayOutputStream()
        handlerForBucket().handleRequest(
            ByteArrayInputStream(proxyEvent(key).toByteArray(Charsets.UTF_8)),
            output,
            context,
        )

        val response = output.toByteArray()

        // Status is committed in the metadata JSON segment before the 8 null-byte delimiter.
        val metadata = extractMetadata(response)
        assertEquals(200, metadata.statusCode, "committed status must be 200 for the ~15 MB object")

        val received = extractBody(response)
        assertEquals(
            payload.size,
            received.size,
            "received body byte count must equal the stored object size (> 6 MB)",
        )
        assertArrayEquals(payload, received, "received body must be byte-identical to the uploaded object")
    }
}
