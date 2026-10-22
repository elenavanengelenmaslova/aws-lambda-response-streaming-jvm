package nl.vintik.lambda.bedrock

import java.io.InputStream

/**
 * Resolves the incoming Lambda [InputStream] to a typed request [R].
 *
 * Mirrors the `streaming-s3-example` abstraction: implementations read and parse the incoming
 * event (an API Gateway proxy event), validate it, and return either a [RequestResult.Resolved]
 * carrying the request object, or a [RequestResult.Error] with an HTTP status and message that the
 * handler writes as an error response without invoking the model.
 */
fun interface RequestResolver<out R : Any> {
    fun resolve(input: InputStream): RequestResult<R>
}

/** Outcome of resolving a typed request from an incoming Lambda event. */
sealed interface RequestResult<out R : Any> {
    data class Resolved<R : Any>(val request: R) : RequestResult<R>
    data class Error(val statusCode: Int, val message: String) : RequestResult<Nothing>
}
