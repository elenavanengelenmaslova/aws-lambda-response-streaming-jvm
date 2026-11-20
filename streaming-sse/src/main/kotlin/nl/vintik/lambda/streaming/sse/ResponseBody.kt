package nl.vintik.lambda.streaming.sse

import java.io.IOException
import java.io.OutputStream

/**
 * The body of a streamed response, for callers that cannot comfortably use a Kotlin function type
 * (notably Java lambdas, where `(OutputStream) -> T` surfaces as `Function1<OutputStream, T>`).
 *
 * Used with [StreamingResponse.stream]. [output] is the live response stream.
 */
public fun interface ResponseBody {
    @Throws(IOException::class)
    public fun write(output: OutputStream)
}
