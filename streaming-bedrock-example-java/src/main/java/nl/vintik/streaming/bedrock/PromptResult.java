package nl.vintik.streaming.bedrock;

/**
 * Outcome of resolving an incoming event into a model request: either a usable request, or a
 * rejection that is answered with an HTTP error before any streaming begins.
 */
sealed interface PromptResult permits PromptResult.Resolved, PromptResult.Rejected {

    /** The event produced a valid request. */
    record Resolved(PromptRequest request) implements PromptResult {
    }

    /** The event was refused; {@code status} and {@code message} describe the HTTP error. */
    record Rejected(int status, String message) implements PromptResult {
    }
}
