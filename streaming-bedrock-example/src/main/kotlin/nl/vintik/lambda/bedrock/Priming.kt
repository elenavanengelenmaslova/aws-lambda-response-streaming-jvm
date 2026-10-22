package nl.vintik.lambda.bedrock

import aws.sdk.kotlin.services.bedrockruntime.BedrockRuntimeClient
import io.github.oshai.kotlinlogging.KotlinLogging
import nl.vintik.lambda.streaming.ResponseMetadata
import nl.vintik.lambda.streaming.ResponseWriter
import org.crac.Core
import org.crac.Resource
import java.io.ByteArrayInputStream
import java.io.OutputStream
import org.crac.Context as CracContext

private val logger = KotlinLogging.logger {}

/**
 * CRaC warm-up hook for SnapStart, mirroring `streaming-s3-example`'s `Priming`. Registered with
 * the global CRaC context at construction so the Lambda runtime invokes [beforeCheckpoint] while
 * the snapshot is created, leaving the restored snapshot warm.
 *
 * [beforeCheckpoint] exercises the local critical paths in one pass — Bedrock client construction,
 * request resolution (JSON parsing), SSE padding encoding, and metadata serialization — WITHOUT
 * invoking the model (no network, no cost). Failures propagate so a broken snapshot is never
 * published.
 */
class Priming(
    private val clientFactory: () -> BedrockRuntimeClient = { BedrockRuntimeClient { } },
    private val resolverFactory: () -> RequestResolver<PromptRequest> = { PromptResolver() },
    private val responseWriterFactory: () -> ResponseWriter = { ResponseWriter() },
) : Resource {

    init {
        Core.getGlobalContext().register(this)
    }

    override fun beforeCheckpoint(context: CracContext<out Resource>?) {
        logger.info { "Priming critical paths before checkpoint" }

        // 1. Bedrock client initialization.
        clientFactory().close()

        // 2. Request resolution path (JSON parsing) against a primed event.
        val primed = """{"body":"{\"prompt\":\"prime\",\"maxTokens\":16}"}"""
        resolverFactory().resolve(ByteArrayInputStream(primed.toByteArray(Charsets.UTF_8)))

        // 3. SSE padding encoding + metadata serialization.
        PaddedSseWriter(headPadBytes = 1024, tailPadBytes = 256, headPadEvents = 1)
            .writeEvent(OutputStream.nullOutputStream(), "prime")
        responseWriterFactory().writeMetadata(
            OutputStream.nullOutputStream(),
            ResponseMetadata(200, mapOf("Content-Type" to "text/event-stream; charset=utf-8")),
        )

        logger.info { "Priming complete" }
    }

    override fun afterRestore(context: CracContext<out Resource>?) = Unit
}
