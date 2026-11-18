package nl.vintik.lambda.bedrock

/**
 * Typed request for the Bedrock streaming endpoint: the user [prompt] and a cap on the generation
 * length in [maxTokens].
 *
 * The SSE padding knobs this used to carry (`headPadBytes`, `tailPadBytes`, `headPadEvents`) are
 * gone. They existed to inflate each event past a supposed ~100 KB buffer in the Lambda
 * response-streaming runtime; measurement showed no such buffer exists — the managed runtime sends
 * nothing at all until the handler returns, at any size. The endpoint now streams for real through
 * `StreamingResponse`, so there is nothing to pad. See `docs/log.md`.
 *
 * Produced by [PromptResolver] and consumed by [BedrockStreamHandler].
 */
data class PromptRequest(
    val prompt: String,
    val maxTokens: Int,
)
