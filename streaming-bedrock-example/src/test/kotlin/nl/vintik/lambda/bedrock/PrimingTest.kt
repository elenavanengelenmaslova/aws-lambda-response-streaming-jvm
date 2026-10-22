package nl.vintik.lambda.bedrock

import aws.sdk.kotlin.services.bedrockruntime.BedrockRuntimeClient
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import nl.vintik.lambda.streaming.ResponseWriter
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class PrimingTest {

    private val client = mockk<BedrockRuntimeClient>(relaxed = true)

    @Test
    fun `Given priming When beforeCheckpoint Then each critical path is exercised without invoking the model`() {
        // Given — factories returning mocks so we can verify each path is touched.
        val resolver = mockk<RequestResolver<PromptRequest>>(relaxed = true)
        val writer = mockk<ResponseWriter>(relaxed = true)
        val priming = Priming(
            clientFactory = { client },
            resolverFactory = { resolver },
            responseWriterFactory = { writer },
        )

        // When
        priming.beforeCheckpoint(null)

        // Then — client built+closed, resolver exercised, metadata serialized. No model invocation.
        verify { client.close() }
        verify { resolver.resolve(any()) }
        verify { writer.writeMetadata(any(), any()) }
    }

    @Test
    fun `Given a failing factory When beforeCheckpoint Then the failure propagates so the snapshot fails`() {
        val priming = Priming(
            clientFactory = { error("boom") },
        )
        assertThrows(IllegalStateException::class.java) { priming.beforeCheckpoint(null) }
    }

    @Test
    fun `Given afterRestore When called Then it is a no-op`() {
        val priming = Priming(clientFactory = { client })
        priming.afterRestore(null) // must not throw
    }
}
