package nl.vintik.lambda.streaming.sse

import nl.vintik.lambda.streaming.ResponseMetadata
import nl.vintik.lambda.streaming.ResponseWriter
import java.io.OutputStream

/**
 * Writes a Lambda response that actually streams: bytes reach the client while the handler is still
 * running.
 *
 * ### What it replaces
 * Writing to the `OutputStream` a `RequestStreamHandler` is given cannot stream on the managed JVM
 * runtime — that stream is a `ByteArrayOutputStream`, and the runtime posts its contents only after
 * `handleRequest` returns. Measured on a managed `java25` function: 15 MB written at 2 ms, first
 * byte observed by API Gateway at 10 069 ms, after a 10-second sleep. No `flush()` changes that,
 * because there is no connection behind the stream.
 *
 * This class posts the response itself, incrementally, and hands you that live stream instead. The
 * prelude, delimiter and body are written in exactly the documented order by `streaming-core`'s
 * [ResponseWriter] — only the destination differs.
 *
 * ### Required setup
 * Streaming needs [StreamingAgent] loaded, because AWS's invocation loop must be stopped from
 * posting a second response for an invocation this class has already answered. Three steps, no
 * wrapper script and no Lambda layer:
 *
 * 1. depend on this module;
 * 2. package the function as a **zip** containing the fat jar under `lib/` (the standard Java Lambda
 *    layout) — `-javaagent` needs a real jar file, and a bare-jar `CodeUri` is extracted loose;
 * 3. add the agent to the function's `JAVA_TOOL_OPTIONS`:
 *    ```yaml
 *    JAVA_TOOL_OPTIONS: "-XX:+TieredCompilation -XX:TieredStopAtLevel=1
 *                        -javaagent:/var/task/lib/<your-jar>.jar"
 *    ```
 *
 * `Runtime: java25`, SnapStart, CRaC priming hooks, warm reuse and your handler signature all stay
 * as they are.
 *
 * ### Usage
 * ```kotlin
 * override fun handleRequest(input: InputStream, output: OutputStream, context: Context) {
 *     val metadata = ResponseMetadata(200, mapOf("Content-Type" to "text/event-stream"))
 *     StreamingResponse().send(context.awsRequestId, metadata) { live ->
 *         val sse = SseWriter()
 *         model.tokens().forEach { sse.writeEvent(live, it) }
 *         sse.writeEnd(live)
 *     }
 * }
 * ```
 *
 * The request id is taken as a `String` rather than a `Context` on purpose: this module depends on
 * no AWS artifact, so it adds nothing to a consumer's classpath beyond `streaming-core`.
 *
 * The `output` the runtime handed you is deliberately not touched.
 *
 * ### Status ordering
 * The same rule as the buffered path: the status is committed the moment the metadata and the
 * 8-byte delimiter are written. Validate before calling [send] — once this returns the first byte,
 * a later failure can only truncate the body.
 *
 * @param responseWriter writes the metadata prelude and delimiter.
 * @param channels opens the response connection; substitute in tests.
 * @param requireAgent when true (the default) [send] fails fast if the agent is not loaded, rather
 *   than streaming into a response AWS's loop will then duplicate and fail on.
 */
public class StreamingResponse @JvmOverloads constructor(
    private val responseWriter: ResponseWriter = ResponseWriter(),
    private val channels: ResponseChannelFactory = RuntimeApiChannelFactory(),
    private val requireAgent: Boolean = true,
) {

    /**
     * Opens the streamed response for the invocation identified by [requestId] (take it from
     * `Context.getAwsRequestId()`), writes [metadata], then runs [body] with the live stream. The
     * response is completed and the invocation marked answered when [body] returns.
     *
     * @throws IllegalStateException if [requireAgent] is set and [StreamingAgent] has not patched
     *   the runtime, or if the Runtime API rejects the response.
     * @return whatever [body] returns.
     */
    public fun <T> send(
        requestId: String,
        metadata: ResponseMetadata,
        body: (OutputStream) -> T,
    ): T {
        check(!requireAgent || StreamingAgent.isPatched()) {
            "Streaming requires the agent: add -javaagent:/var/task/lib/<your-jar>.jar to " +
                "JAVA_TOOL_OPTIONS and package the function as a zip with the jar under lib/. " +
                "See the streaming-sse README."
        }
        require(requestId.isNotBlank()) { "requestId must not be blank" }

        // Cleared before anything is posted: a reused execution environment must never carry an
        // "answered" flag into the next invocation.
        StreamingState.reset()

        val channel = channels.open(requestId)
        val result = try {
            responseWriter.writeMetadata(channel.output, metadata)
            channel.output.flush()
            body(channel.output)
        } catch (e: Throwable) {
            // The body is already committed, so the status cannot be rewritten. Complete the
            // request anyway: leaving it open would stall the client until a timeout. The original
            // failure is what the caller needs, so a completion failure is suppressed onto it.
            runCatching { channel.complete() }.onFailure(e::addSuppressed)
            throw e
        }

        val status = channel.complete()
        check(status == RUNTIME_API_ACCEPTED) {
            "The Lambda Runtime API rejected the streamed response with HTTP $status"
        }
        // Only now: AWS's loop must skip its own post for this invocation.
        StreamingState.markAnswered()
        return result
    }
}
