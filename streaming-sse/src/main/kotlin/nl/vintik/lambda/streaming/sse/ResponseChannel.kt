package nl.vintik.lambda.streaming.sse

import nl.vintik.lambda.streaming.METADATA_PRELUDE_CONTENT_TYPE
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URI

/** HTTP status the Lambda Runtime API returns for an accepted invocation response. */
public const val RUNTIME_API_ACCEPTED: Int = 202

/**
 * One in-flight streamed response. Implementations own a live connection whose body is written
 * incrementally, which is what makes bytes leave the execution environment before the handler
 * returns.
 *
 * Exists as an interface so [StreamingResponse] can be exercised without a Lambda runtime: tests
 * supply a channel backed by an in-memory stream.
 */
public interface ResponseChannel {

    /** The live body stream. Writes reach the network as they are flushed. */
    public val output: OutputStream

    /** Finishes the body and returns the HTTP status, normally [RUNTIME_API_ACCEPTED]. */
    public fun complete(): Int
}

/** Opens a [ResponseChannel] for one invocation. */
public fun interface ResponseChannelFactory {
    public fun open(requestId: String): ResponseChannel
}

/**
 * Opens the real chunked `POST` to the Lambda Runtime API that a custom runtime would use:
 *
 * ```
 * POST http://$AWS_LAMBDA_RUNTIME_API/2018-06-01/runtime/invocation/{requestId}/response
 * Lambda-Runtime-Function-Response-Mode: streaming
 * Content-Type: application/vnd.awslambda.http-integration-response
 * Transfer-Encoding: chunked
 * ```
 *
 * The content type is the marker Node's `awslambda.HttpResponseStream.from()` sets through
 * `setContentType`: it tells the service the body opens with the metadata prelude. Chunked transfer
 * encoding plus a `flush()` per write is what emits each small event as its own chunk — a 24-byte
 * event does leave immediately rather than waiting for a buffer to fill.
 *
 * @param endpoint runtime API host:port, taken from `AWS_LAMBDA_RUNTIME_API` when not supplied.
 * @param connectTimeoutMs connect timeout for the response post.
 * @param readTimeoutMs how long to wait for the Runtime API's status after the body is finished.
 */
public class RuntimeApiChannelFactory @JvmOverloads constructor(
    private val endpoint: String? = System.getenv(RUNTIME_API_ENV),
    private val connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
    private val readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS,
) : ResponseChannelFactory {

    override fun open(requestId: String): ResponseChannel {
        val host = endpoint
        checkNotNull(host) {
            "$RUNTIME_API_ENV is not set; streaming is only available inside a Lambda execution environment"
        }
        require(requestId.isNotBlank()) { "requestId must not be blank" }

        val url = URI("http://$host/$RESPONSE_PATH_PREFIX/$requestId/response").toURL()
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            useCaches = false
            // 0 lets the implementation pick a chunk size; a flush still emits a partial chunk.
            setChunkedStreamingMode(0)
            setRequestProperty(RESPONSE_MODE_HEADER, RESPONSE_MODE_STREAMING)
            setRequestProperty("Content-Type", METADATA_PRELUDE_CONTENT_TYPE)
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
        }
        return HttpResponseChannel(connection)
    }

    public companion object {
        /** Environment variable holding the Runtime API endpoint inside a Lambda sandbox. */
        public const val RUNTIME_API_ENV: String = "AWS_LAMBDA_RUNTIME_API"

        internal const val RESPONSE_PATH_PREFIX = "2018-06-01/runtime/invocation"
        internal const val RESPONSE_MODE_HEADER = "Lambda-Runtime-Function-Response-Mode"
        internal const val RESPONSE_MODE_STREAMING = "streaming"
        private const val DEFAULT_CONNECT_TIMEOUT_MS = 5_000
        private const val DEFAULT_READ_TIMEOUT_MS = 60_000
    }
}

/** [ResponseChannel] over an [HttpURLConnection] in chunked streaming mode. */
internal class HttpResponseChannel(private val connection: HttpURLConnection) : ResponseChannel {

    override val output: OutputStream by lazy { connection.outputStream }

    override fun complete(): Int {
        // Closing the body first: the Runtime API only answers once the chunked request ends.
        runCatching { output.close() }
        return try {
            connection.responseCode
        } finally {
            connection.disconnect()
        }
    }
}
