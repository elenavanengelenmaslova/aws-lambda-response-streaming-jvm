package nl.vintik.streaming.bedrock;

import java.io.IOException;

/** A source of text deltas for a prompt. Writes each delta to the sink on the calling thread. */
@FunctionalInterface
interface TokenStream {
    void stream(PromptRequest request, Deadline deadline, DeltaSink sink) throws IOException;
}
