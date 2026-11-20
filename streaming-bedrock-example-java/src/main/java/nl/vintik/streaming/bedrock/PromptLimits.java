package nl.vintik.streaming.bedrock;

/** Defaults and upper bounds for prompt handling, so a request cannot ask for unbounded cost. */
final class PromptLimits {

    /** Long enough that an empty request body still produces an answer worth watching stream. */
    static final String DEFAULT_PROMPT =
            "Explain in flowing prose why HTTP response streaming matters for serverless applications.";

    static final int DEFAULT_MAX_TOKENS = 1000;

    /** Longest accepted prompt, in characters. */
    static final int MAX_PROMPT_LEN = 8000;

    /** Largest token count passed to the model; larger requests are clamped to this. */
    static final int MAX_TOKENS_CEILING = 5000;

    private PromptLimits() {
    }
}
