package nl.vintik.lambda.bedrock

import aws.sdk.kotlin.services.bedrockruntime.BedrockRuntimeClient
import aws.sdk.kotlin.services.bedrockruntime.model.InvokeModelWithResponseStreamRequest
import aws.sdk.kotlin.services.bedrockruntime.model.ResponseStream
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private val logger = KotlinLogging.logger {}

/** Amazon Nova Lite EU cross-region inference profile id. */
const val NOVA_LITE_PROFILE_ID: String = "eu.amazon.nova-lite-v1:0"

/**
 * [TokenStreamSource] backed by Amazon Bedrock Nova Lite via the Kotlin AWS SDK's
 * `InvokeModelWithResponseStream`. Builds the Nova "messages-v1" request body, consumes the
 * response-event [kotlinx.coroutines.flow.Flow], and forwards each decoded text delta to `onDelta`.
 *
 * The model id and prompt are never logged.
 */
class BedrockSource(
    private val client: BedrockRuntimeClient = defaultClient(),
    private val modelId: String = NOVA_LITE_PROFILE_ID,
) : TokenStreamSource<PromptRequest> {

    override suspend fun stream(request: PromptRequest, onDelta: (String) -> Unit) {
        val invoke = InvokeModelWithResponseStreamRequest {
            modelId = this@BedrockSource.modelId
            contentType = "application/json"
            accept = "application/json"
            body = novaRequestBody(request.prompt, request.maxTokens).toByteArray(Charsets.UTF_8)
        }
        client.invokeModelWithResponseStream(invoke) { response ->
            response.body?.collect { event ->
                if (event is ResponseStream.Chunk) {
                    val bytes = event.value.bytes
                    if (bytes != null) {
                        val text = extractNovaTextDelta(bytes.decodeToString())
                        if (!text.isNullOrEmpty()) onDelta(text)
                    }
                }
            }
        }
    }

    companion object {
        private fun defaultClient(): BedrockRuntimeClient = BedrockRuntimeClient { }
    }
}

private val novaJson = Json { ignoreUnknownKeys = true }

/** Builds the Nova "messages-v1" request body for a single user prompt and token cap. */
internal fun novaRequestBody(prompt: String, maxTokens: Int): String {
    val escaped = novaJson.encodeToString(JsonPrimitive(prompt))
    return """
        {"schemaVersion":"messages-v1",
         "messages":[{"role":"user","content":[{"text":$escaped}]}],
         "inferenceConfig":{"maxTokens":$maxTokens,"temperature":0.7}}
    """.trimIndent()
}

/**
 * Extracts the incremental text from a Nova streaming event payload, or null when the event carries
 * no text (messageStart, contentBlockStop, metadata, etc.). Nova emits incremental text as
 * `{"contentBlockDelta":{"delta":{"text":"..."}}}`.
 */
internal fun extractNovaTextDelta(payloadJson: String): String? =
    runCatching {
        val root = novaJson.parseToJsonElement(payloadJson).jsonObject
        val delta = (root["contentBlockDelta"] as? JsonObject)?.get("delta") as? JsonObject
        delta?.get("text")?.jsonPrimitive?.content
    }.getOrNull()
