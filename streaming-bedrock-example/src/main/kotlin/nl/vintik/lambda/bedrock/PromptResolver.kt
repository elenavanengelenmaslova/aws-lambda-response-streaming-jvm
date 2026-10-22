package nl.vintik.lambda.bedrock

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.InputStream

private val logger = KotlinLogging.logger {}

/** Defaults chosen to make the padding workaround visible out of the box (see module README). */
internal const val DEFAULT_PROMPT =
    "Explain in flowing prose why HTTP response streaming matters for serverless applications."
internal const val DEFAULT_HEAD_PAD_BYTES = 100 * 1024 // break the initial ~100 KB runtime buffer
internal const val DEFAULT_TAIL_PAD_BYTES = 10 * 1024  // keep the stream flowing afterwards
internal const val DEFAULT_HEAD_PAD_EVENTS = 3
internal const val DEFAULT_MAX_TOKENS = 1000

/** Upper bounds so a request cannot ask for an unboundedly expensive generation or payload. */
internal const val MAX_PROMPT_LEN = 8_000
internal const val MAX_TOKENS_CEILING = 5_000
internal const val MAX_PAD_BYTES = 1_048_576

/**
 * [RequestResolver] for the Bedrock streaming endpoint. Parses the API Gateway proxy event, reads
 * the client payload from its `body` field (itself JSON: `{"prompt":..., "maxTokens":..., ...}`),
 * validates the prompt, and clamps the padding/token knobs to safe bounds.
 *
 * A missing or blank prompt falls back to [DEFAULT_PROMPT] so the endpoint is demoable with an
 * empty body. A prompt longer than [MAX_PROMPT_LEN] is rejected with HTTP 400 and no model call.
 */
class PromptResolver(
    private val json: Json = Json { ignoreUnknownKeys = true },
) : RequestResolver<PromptRequest> {

    override fun resolve(input: InputStream): RequestResult<PromptRequest> {
        val raw = runCatching { input.readBytes().decodeToString() }.getOrElse { "" }.trim()
        val body = parseBody(raw)

        val prompt = (body?.get("prompt")?.jsonPrimitive?.content ?: DEFAULT_PROMPT)
            .ifBlank { DEFAULT_PROMPT }
        if (prompt.length > MAX_PROMPT_LEN) {
            logger.warn { "Prompt rejected (length=${prompt.length} > $MAX_PROMPT_LEN); responding 400" }
            return RequestResult.Error(400, "The prompt is too long.")
        }

        fun clampedInt(key: String, default: Int, min: Int, max: Int): Int {
            val v = body?.get(key)?.jsonPrimitive?.intOrNull() ?: default
            return v.coerceIn(min, max)
        }

        return RequestResult.Resolved(
            PromptRequest(
                prompt = prompt,
                headPadBytes = clampedInt("headPadBytes", DEFAULT_HEAD_PAD_BYTES, 0, MAX_PAD_BYTES),
                tailPadBytes = clampedInt("tailPadBytes", DEFAULT_TAIL_PAD_BYTES, 0, MAX_PAD_BYTES),
                headPadEvents = clampedInt("headPadEvents", DEFAULT_HEAD_PAD_EVENTS, 0, 100),
                maxTokens = clampedInt("maxTokens", DEFAULT_MAX_TOKENS, 1, MAX_TOKENS_CEILING),
            ),
        )
    }

    /**
     * Extracts the client JSON object from the event. Accepts a raw JSON body or an API Gateway
     * proxy event whose `body` field holds the client JSON. Returns null when nothing parses, in
     * which case defaults apply.
     */
    private fun parseBody(raw: String): kotlinx.serialization.json.JsonObject? {
        if (raw.isEmpty()) return null
        return runCatching {
            val root = json.parseToJsonElement(raw).jsonObject
            val bodyStr = root["body"]?.jsonPrimitive?.content
            if (bodyStr != null) json.parseToJsonElement(bodyStr).jsonObject else root
        }.getOrNull()
    }

    private fun kotlinx.serialization.json.JsonPrimitive.intOrNull(): Int? =
        runCatching { this.int }.getOrNull()
}
