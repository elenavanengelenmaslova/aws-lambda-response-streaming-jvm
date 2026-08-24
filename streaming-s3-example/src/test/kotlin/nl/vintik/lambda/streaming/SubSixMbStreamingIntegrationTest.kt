package nl.vintik.lambda.streaming

import com.amazonaws.services.lambda.runtime.Context
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.random.Random

/**
 * Floci S3 integration test proving the sub-6 MB streaming path end to end (Req 5.6).
 *
 * Container lifecycle, the S3 client, object cleanup, and protocol parsing all come from
 * [FlociS3IntegrationTestBase]; this class contributes only the fixture and the assertions.
 *
 * The test uploads a sub-6 MB object through the real Kotlin AWS SDK pointed at the emulator, then
 * drives the production [StreamHandler] with a synthetic API Gateway proxy event. The handler runs
 * the full pipeline — parse -> validate -> head -> write metadata + 8 null-byte delimiter + body —
 * against the live S3 endpoint, and the received body bytes are asserted byte-identical to the
 * uploaded object (Req 5.6).
 */
class SubSixMbStreamingIntegrationTest : FlociS3IntegrationTestBase() {

    private val context: Context = mockk(relaxed = true)

    @Test
    fun `Given a sub-6 MB S3 object When streamed through the handler Then the received body is byte-identical`() {
        // 5 MB of pseudo-random bytes: comfortably under the legacy 6 MB buffered limit,
        // larger than the 1 MB transfer buffer so multiple chunks are exercised.
        val key = "sub-six.bin"
        val payload = Random(42).nextBytes(5 * 1024 * 1024)
        upload(key, payload)

        val output = ByteArrayOutputStream()
        handlerForBucket().handleRequest(
            ByteArrayInputStream(proxyEvent(key).toByteArray(Charsets.UTF_8)),
            output,
            context,
        )

        val received = extractBody(output.toByteArray())
        assertEquals(payload.size, received.size, "received body length must equal the uploaded object size")
        assertArrayEquals(payload, received, "received body must be byte-identical to the uploaded object")
    }
}
