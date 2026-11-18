package nl.vintik.lambda.streaming

import com.amazonaws.services.lambda.runtime.Context
import com.amazonaws.services.lambda.runtime.RequestStreamHandler
import java.io.InputStream
import java.io.OutputStream

class S3StreamingHandler : RequestStreamHandler {
    private val handler = StreamHandler(
        requestResolver = ::FileKeyResolver,
        source = ::S3Source,
    )

    override fun handleRequest(input: InputStream, output: OutputStream, context: Context) =
        handler.handleRequest(input, output, context)

    private companion object {
        /**
         * Eagerly constructed so [Priming] registers itself with the global CRaC context during
         * this class's initialization, which is what makes SnapStart run `beforeCheckpoint` while
         * the snapshot is taken.
         *
         * [Priming] registers in its own constructor, so without a construction site the hook is
         * never registered and no critical path is warmed — the state this example was in until
         * now (see `docs/log.md`). `PRIMING` is referenced by nothing at runtime; its only job is
         * to exist. Mirrors `streaming-s3-example-java`'s
         * `private static final Priming PRIMING = new Priming();`.
         */
        @Suppress("unused")
        private val PRIMING = Priming()
    }
}
