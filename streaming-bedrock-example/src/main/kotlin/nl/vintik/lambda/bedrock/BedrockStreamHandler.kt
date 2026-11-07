package nl.vintik.lambda.bedrock

import com.amazonaws.services.lambda.runtime.Context
import com.amazonaws.services.lambda.runtime.RequestStreamHandler
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking
import nl.vintik.lambda.streaming.ResponseMetadata
import nl.vintik.lambda.streaming.ResponseWriter
import nl.vintik.lambda.streaming.sse.PaddedSseWriter
import java.io.InputStream
import java.io.OutputStream

private val logger = KotlinLogging.logger {}

/**
 * Orchestrates the Bedrock streaming pipeline, mirroring `streaming-s3-example`'s `StreamHandler`:
 * resolve the typed [PromptRequest], commit the response status once via the `streaming-core`
 * [ResponseWriter], then relay model text deltas as padded SSE events through [PaddedSseWriter].
 *
 * Collaborators are injected by delegation (`by lazy` fed by factories) so production uses the real
 * defaults while tests substitute mocks, and the Bedrock client is built only on the first real
 * invocation.
 *
 * Ordering guarantees (same head-before-commit principle as the S3 example):
 *  - A request-resolution error writes a metadata-only error response and no body (status 400).
 *  - For a resolved request, the 200 metadata is deferred until the FIRST model delta arrives, so
 *    the status is not committed until the model has actually started producing output. A model
 *    failure BEFORE the first delta is therefore mapped to a 502 error response (status not yet
 *    committed). A successful generation that produces no deltas still returns 200.
 *  - Once the first delta has been written the status is committed; a later failure can only
 *    truncate the body and propagate — it can never rewrite the status.
 *  - Metadata is written exactly once (`Content-Type: text/event-stream`, `Transfer-Encoding:
 *    chunked` — no `Content-Length`, the length is unknown).
 *  - The output stream is flushed and closed by this handler before returning.
 */
class BedrockStreamHandler(
    requestResolver: () -> RequestResolver<PromptRequest>,
    source: () -> TokenStreamSource<PromptRequest>,
    responseWriter: () -> ResponseWriter = ::ResponseWriter,
) : RequestStreamHandler {

    private val requestResolver: RequestResolver<PromptRequest> by lazy(requestResolver)
    private val source: TokenStreamSource<PromptRequest> by lazy(source)
    private val responseWriter: ResponseWriter by lazy(responseWriter)

    override fun handleRequest(input: InputStream, output: OutputStream, context: Context) {
        output.use {
            when (val result = requestResolver.resolve(input)) {
                is RequestResult.Error -> responseWriter.writeError(output, result.statusCode, result.message)
                is RequestResult.Resolved -> stream(result.request, output)
            }
            output.flush()
        }
    }

    private fun stream(request: PromptRequest, output: OutputStream) {
        val sse = PaddedSseWriter(request.headPadBytes, request.tailPadBytes, request.headPadEvents)
        val start = System.currentTimeMillis()
        var committed = false

        fun commitMetadataOnce() {
            if (committed) return
            responseWriter.writeMetadata(
                output,
                ResponseMetadata(
                    statusCode = 200,
                    headers = mapOf(
                        "Content-Type" to "text/event-stream; charset=utf-8",
                        "Cache-Control" to "no-cache",
                        "Transfer-Encoding" to "chunked",
                    ),
                ),
            )
            committed = true
        }

        // Metadata is deferred until the first delta: the model must actually start producing
        // output before the 200 status is committed, so a pre-first-delta failure can still be a 502.
        val outcome = runCatching {
            runBlocking {
                source.stream(request) { delta ->
                    commitMetadataOnce()
                    sse.writeEvent(output, delta)
                    logger.debug { "event=${sse.eventsWritten()} elapsedMs=${System.currentTimeMillis() - start}" }
                }
            }
        }

        outcome.fold(
            onSuccess = {
                // A successful generation with zero deltas still returns 200.
                commitMetadataOnce()
                sse.writeEnd(output)
                logger.info { "stream complete events=${sse.eventsWritten()}" }
            },
            onFailure = { e ->
                if (!committed) {
                    // Failure before any delta: status not yet committed, so return a 502.
                    logger.warn(e) { "Bedrock stream failed before the first delta; responding 502" }
                    responseWriter.writeError(output, 502, "Model streaming failed.")
                } else {
                    // Status already committed: a failure can only truncate the body and propagate.
                    logger.error(e) { "Bedrock relay failed after status commit; body truncated at event=${sse.eventsWritten()}" }
                    throw e
                }
            },
        )
    }
}
