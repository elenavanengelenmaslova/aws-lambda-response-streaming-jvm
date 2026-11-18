package nl.vintik.lambda.streaming.sse

/**
 * The flag shared between your handler and the patched Lambda runtime class.
 *
 * ### Why it exists
 * On the managed JVM runtime the `OutputStream` your handler receives is a `ByteArrayOutputStream`:
 * everything written accumulates in memory and is posted only after `handleRequest` returns, so no
 * byte can reach the client while the handler is still running. [StreamingResponse] side-steps that
 * by posting the response itself, as a chunked request to the Lambda Runtime API. AWS's invocation
 * loop does not know the invocation has been answered, so it posts a **second** response, which
 * fails and takes the runtime process down with `Runtime.ExitError`.
 *
 * [StreamingAgent] patches the runtime's response-posting method to return early when
 * [alreadyAnswered] is true. That is the only change made to AWS's loop — everything else it does,
 * including driving CRaC/SnapStart hooks, is left alone.
 *
 * You do not normally call this: [StreamingResponse] sets and clears it for you.
 *
 * ### Thread scope
 * State is per-thread, because the handler and the runtime's response post run on the same thread
 * for a given invocation. A provider that runs several invocations concurrently in one execution
 * environment therefore keeps them independent, and a reused environment cannot leak an "answered"
 * flag into a later request — which would silently suppress a legitimate response.
 */
public object StreamingState {

    private val answered = ThreadLocal.withInitial { false }

    /**
     * Marks this invocation as already answered. Called after a streamed response completes.
     *
     * `@JvmStatic` is required, not cosmetic: the agent injects an `INVOKESTATIC` call to
     * [alreadyAnswered], and without a static bridge the patched runtime class fails to link with
     * `IncompatibleClassChangeError`. All three members carry it so the object's shape stays
     * consistent for Java callers.
     */
    @JvmStatic
    public fun markAnswered() {
        answered.set(true)
    }

    /**
     * True when this invocation's response has already been posted.
     *
     * Called from the patched runtime method, so it must stay cheap, must never throw, and must
     * remain a static method with the descriptor `()Z`.
     */
    @JvmStatic
    public fun alreadyAnswered(): Boolean = answered.get()

    /** Clears the flag. Called at the start of a streamed response, before anything is posted. */
    @JvmStatic
    public fun reset() {
        answered.set(false)
    }
}
