package nl.vintik.streaming.bedrock;

import java.io.IOException;

/** Receives each text delta of the model's answer, on the invocation thread. */
@FunctionalInterface
interface DeltaSink {
    void accept(String text) throws IOException;
}
