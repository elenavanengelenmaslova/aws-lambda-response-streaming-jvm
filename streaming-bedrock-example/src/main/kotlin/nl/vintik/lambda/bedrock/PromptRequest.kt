package nl.vintik.lambda.bedrock

/**
 * Typed request for the Bedrock streaming endpoint.
 *
 * Carries the user [prompt] plus the tiered SSE-comment padding configuration used to defeat the
 * AWS Lambda response-streaming runtime's internal buffer (see [PaddedSseWriter] and the module
 * README). [headPadBytes] is applied to the first [headPadEvents] events to break the initial
 * buffer; [tailPadBytes] is applied to every event afterwards to keep the stream flowing.
 * [maxTokens] caps the model generation.
 *
 * Produced by [PromptResolver] and consumed by [BedrockStreamHandler].
 */
data class PromptRequest(
    val prompt: String,
    val headPadBytes: Int,
    val tailPadBytes: Int,
    val headPadEvents: Int,
    val maxTokens: Int,
)
