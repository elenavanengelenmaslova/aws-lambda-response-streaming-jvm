package nl.vintik.lambda.bedrock

import com.amazonaws.services.lambda.runtime.Context
import com.amazonaws.services.lambda.runtime.RequestStreamHandler
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking
import nl.vintik.lambda.streaming.ResponseMetadata
import nl.vintik.lambda.streaming.ResponseWriter
import nl.vintik.lambda.streaming.sse.SseWriter
import nl.vintik.lambda.streaming.sse.StreamingResponse
import java.io.InputStream
import java.io.OutputStream

private val logger = KotlinLogging.logger {}

/**
 * Relays Amazon Bedrock Nova Lite token deltas to the client as Server-Sent Events that arrive
 * **token by token**.
 *
 * ### How the streaming works
 * The `OutputStream` the Lambda runtime hands a `RequestStreamHandler` is a
 * `ByteArrayOutputStream`: writes accumulate in memory and are posted only after `handleRequest`
 * returns, so nothing can reach the client mid-invocation. This handler therefore ignores it and
 * writes through [StreamingResponse], which posts the response itself as a chunked request to the
 * Lambda Runtime API and hands back that live stream. Each [SseWriter] event is flushed and leaves
 * immediately — measured 19–46 ms to API Gateway for a 24-byte event.
 *
 * Deploying this needs two things beyond the dependency: the function packaged as a zip with the fat
 * jar under `lib/`, and `-javaagent:/var/task/lib/<jar>` on `JAVA_TOOL_OPTIONS`. See the
 * `streaming-sse` README; the SAM template in `deployment/aws/sam-bedrock/` has both.
 *
 * ### What this replaces
 * This endpoint used to pad every SSE event with ~10–100 KB of SSE comments to defeat what was
 * believed to be a ~100 KB buffer inside the runtime. There is no such buffer, and padding only
 * inflated the body until downloading it took long enough to look progressive: 18 MB on the wire
 * for a first token at 70% of total, against 14 KB for a first token at 2%. See `docs/log.md`.
 *
 * ### Ordering guarantees
 * - A request-resolution error is answered **buffered**, on the runtime's own stream, with no body
 *   bytes — nothing has been committed at that point, so the status is still free (400).
 * - A model failure before the first delta is likewise answered buffered as a 502.
 * - The 200 is committed only when the first delta arrives, by which point the model has actually
 *   started producing output. After that a failure can only truncate the body.
 * - A successful generation that produces no deltas still returns 200 with just the terminal event.
 */
class BedrockStreamHandler(
    requestResolver: () -> RequestResolver<PromptRequest>,
    source: () -> TokenStreamSource<PromptRequest>,
    responseWriter: () -> ResponseWriter = ::ResponseWriter,
    streaming: () -> StreamingResponse = ::StreamingResponse,
) : RequestStreamHandler {

    private val requestResolver: RequestResolver<PromptRequest> by lazy(requestResolver)
    private val source: TokenStreamSource<PromptRequest> by lazy(source)
    private val responseWriter: ResponseWriter by lazy(responseWriter)
    private val streaming: StreamingResponse by lazy(streaming)

    override fun handleRequest(input: InputStream, output: OutputStream, context: Context) {
        val timings = StreamTimings(context.awsRequestId.orEmpty())
        timings.mark("start", "outputClass=${output::class.java.name}")

        when (val result = requestResolver.resolve(input)) {
            // Pre-commit errors go back on the runtime's own stream: buffered is fine for a short
            // JSON body, and it keeps the error path independent of the streaming machinery.
            is RequestResult.Error -> output.use {
                responseWriter.writeError(output, result.statusCode, result.message)
            }

            is RequestResult.Resolved -> stream(result.request, output, context, timings)
        }
        timings.mark("closed")
    }

    private fun stream(
        request: PromptRequest,
        bufferedOutput: OutputStream,
        context: Context,
        timings: StreamTimings,
    ) {
        val sse = SseWriter()
        var firstDeltaSeen = false

        val outcome = runCatching {
            streaming.send(context.awsRequestId, sseMetadata()) { live ->
                timings.timed("metadataCommitted") { }
                runBlocking {
                    source.stream(request) { delta ->
                        timings.onDelta()
                        firstDeltaSeen = true
                        timings.timeEventWrite(sse.eventsWritten() + 1) { sse.writeEvent(live, delta) }
                    }
                }
                sse.writeEnd(live)
                timings.streamEnd(sse.eventsWritten())
            }
        }.rethrowFatal()

        outcome.fold(
            onSuccess = { logger.info { "stream complete events=${sse.eventsWritten()}" } },
            onFailure = { e ->
                if (!firstDeltaSeen) {
                    // Nothing was delivered, so the status is still free: answer buffered with 502.
                    logger.warn(e) { "Bedrock stream failed before the first delta; responding 502" }
                    bufferedOutput.use {
                        responseWriter.writeError(bufferedOutput, 502, "Model streaming failed.")
                    }
                } else {
                    logger.error(e) { "Bedrock relay failed after status commit; body truncated at event=${sse.eventsWritten()}" }
                    throw e
                }
            },
        )
    }

    /** SSE response metadata. No `Content-Length`: the answer's length is unknown up front. */
    private fun sseMetadata() = ResponseMetadata(
        statusCode = 200,
        headers = mapOf(
            "Content-Type" to "text/event-stream; charset=utf-8",
            "Cache-Control" to "no-cache",
            "Transfer-Encoding" to "chunked",
        ),
    )
}
