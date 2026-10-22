package nl.vintik.lambda.bedrock

import com.amazonaws.services.lambda.runtime.Context
import com.amazonaws.services.lambda.runtime.RequestStreamHandler
import java.io.InputStream
import java.io.OutputStream

/**
 * No-arg Lambda entrypoint, mirroring `streaming-s3-example`'s `S3StreamingHandler`. Wires the
 * production collaborators ([PromptResolver] + [BedrockSource]) into [BedrockStreamHandler].
 * Referenced by the SAM template's `Handler`.
 */
class BedrockStreamingHandler : RequestStreamHandler {
    private val handler = BedrockStreamHandler(
        requestResolver = ::PromptResolver,
        source = ::BedrockSource,
    )

    override fun handleRequest(input: InputStream, output: OutputStream, context: Context) =
        handler.handleRequest(input, output, context)
}
