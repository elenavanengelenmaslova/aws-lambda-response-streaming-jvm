package nl.vintik.lambda.streaming

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import nl.vintik.lambda.streaming.ValidationResult.Reason
import java.io.InputStream

private val logger = KotlinLogging.logger {}

/**
 * [RequestResolver] implementation for an AWS Lambda Function URL file-serving endpoint.
 *
 * The Function URL delivers a Lambda payload format 2.0 event (distinct from the API
 * Gateway proxy event). This resolver reads a minimal lenient projection of that event —
 * `rawPath`, falling back to `requestContext.http.path` — and lifts the requested file name
 * from it, then runs the **shared** [FileNameValidator]. All other event fields (including
 * the `requestContext.authorizer.iam` section present under `AuthType: AWS_IAM`) are
 * tolerated and ignored.
 *
 * The status mapping is identical to [FileKeyResolver]: a decode failure maps to HTTP 400,
 * every validation rejection maps to HTTP 400 with the same specific message, and no source
 * request is issued for any rejected input. A valid name yields [RequestResult.Resolved]
 * carrying a [FileRequest].
 */
class FunctionUrlResolver(
    private val validator: FileNameValidator = FileNameValidator(),
    private val json: Json = LenientJson,
) : RequestResolver<FileRequest> {

    override fun resolve(input: InputStream): RequestResult<FileRequest> =
        runCatching {
            val raw = input.readBytes().decodeToString()
            json.decodeFromString<FunctionUrlEvent>(raw)
        }.fold(
            onSuccess = { event -> resolveFileName(fileNameFrom(event)) },
            onFailure = {
                logger.warn(it) { "Function URL payload-2.0 event could not be parsed; responding 400" }
                RequestResult.Error(400, "The request could not be parsed.")
            },
        )

    private fun fileNameFrom(event: FunctionUrlEvent): String =
        event.requestedPath().trimStart('/').substringAfterLast('/')

    private fun resolveFileName(fileName: String): RequestResult<FileRequest> {
        return when (val validation = validator.validate(fileName)) {
            is ValidationResult.Invalid -> {
                val message = when (validation.reason) {
                    Reason.MISSING -> "The file name is missing."
                    Reason.TOO_LONG -> "The file name is invalid."
                    Reason.ILLEGAL_CHARACTER,
                    Reason.PATH_SEPARATOR,
                    Reason.PARENT_DIR,
                    Reason.ABSOLUTE_PATH,
                    -> "The file name was rejected."
                }
                logger.warn { "File name rejected (reason=${validation.reason}); responding 400 with no source request" }
                RequestResult.Error(400, message)
            }

            is ValidationResult.Valid -> RequestResult.Resolved(FileRequest(validation.fileName))
        }
    }
}

/**
 * Minimal lenient projection of the Lambda Function URL payload-2.0 event: only the request
 * path is read so the requested file name can be lifted out. All other fields (including the
 * IAM authorizer section) are ignored via the lenient [Json] (`ignoreUnknownKeys = true`).
 */
@Serializable
private data class FunctionUrlEvent(
    val rawPath: String? = null,
    val requestContext: RequestContext? = null,
) {
    @Serializable
    data class RequestContext(val http: Http? = null)

    @Serializable
    data class Http(val path: String? = null)

    /**
     * The requested path — `rawPath` when present, otherwise `requestContext.http.path`,
     * or an empty string when both are absent (left for the validator to reject).
     */
    fun requestedPath(): String = (rawPath ?: requestContext?.http?.path).orEmpty()
}
