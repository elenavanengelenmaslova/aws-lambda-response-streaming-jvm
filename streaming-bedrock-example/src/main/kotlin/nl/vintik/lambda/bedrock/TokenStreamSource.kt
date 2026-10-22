package nl.vintik.lambda.bedrock

/**
 * Contract for a source that produces a model's output as a stream of text deltas, driven by a
 * typed request [R].
 *
 * This is the token-streaming analogue of `streaming-s3-example`'s `StreamSource`. An S3 object has
 * a known size, so there `head` returns a size and `streamBody` copies a body; a model response has
 * no known length, so here the source simply emits text deltas as they are generated, and the
 * handler writes each one immediately. Any backing model (Bedrock, a local LLM, a mock) can
 * implement this and be wired into [BedrockStreamHandler] unchanged.
 */
fun interface TokenStreamSource<in R : Any> {
    /**
     * Invokes the model for [request] and calls [onDelta] once per text delta, in generation order,
     * as each arrives. Must suspend until generation completes. Any failure propagates to the
     * handler, which maps a pre-first-delta failure to an error response and a mid-stream failure to
     * a truncated body.
     */
    suspend fun stream(request: R, onDelta: (String) -> Unit)
}
