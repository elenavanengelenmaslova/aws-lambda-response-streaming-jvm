package nl.vintik.lambda.bedrock

import com.amazonaws.services.lambda.runtime.Context
import com.amazonaws.services.lambda.runtime.RequestStreamHandler
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking
import nl.vintik.lambda.streaming.ResponseMetadata
import nl.vintik.lambda.streaming.ResponseWriter
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
 * Ordering guarantees (same contract as the S3 example):
 *  - A request-resolution error writes a metadata-only error response and no body (status 400).
 *  - For a resolved request, metadata is written exactly once (`Content-Type: text/event-stream`,
 *    `Transfer-Encoding: chunked` — no `Content-Length`, the length is unknown) before any event;
 *    the status is committed at that point.
 *  - A model failure before the first delta is mapped to a 502 ONLY if no metadata was written yet;
 *    once streaming has begun a failure can only truncate the body and propagate.
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
        // Status committed. From here a failure can only truncate the body — never rewrite status.
        val sse = PaddedSseWriter(request.headPadBytes, request.tailPadBytes, request.headPadEvents)
        val start = System.currentTimeMillis()
        runCatching {
            runBlocking {
                source.stream(request) { delta ->
                    sse.writeEvent(output, delta)
                    logger.debug { "event=${sse.eventsWritten()} elapsedMs=${System.currentTimeMillis() - start}" }
                }
            }
        }.onFailure { e ->
            logger.error(e) { "Bedrock relay failed after status commit; body truncated at event=${sse.eventsWritten()}" }
        }.getOrThrow()
        sse.writeEnd(output)
        logger.info { "stream complete events=${sse.eventsWritten()}" }
    }
}
