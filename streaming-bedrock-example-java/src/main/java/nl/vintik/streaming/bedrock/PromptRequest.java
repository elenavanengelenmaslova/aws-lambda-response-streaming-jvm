package nl.vintik.streaming.bedrock;

/**
 * A validated request to the model: the prompt text and the number of tokens to generate.
 *
 * @param prompt    the prompt, never blank and at most {@link PromptLimits#MAX_PROMPT_LEN} characters
 * @param maxTokens the generation limit, within {@code [1, PromptLimits.MAX_TOKENS_CEILING]}
 */
record PromptRequest(String prompt, int maxTokens) {
}
