package nl.vintik.streaming.java;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestStreamHandler;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import kotlinx.serialization.json.Json;
import nl.vintik.lambda.streaming.ResponseWriter;
import nl.vintik.lambda.streaming.ResponseWriterKt;

/**
 * AWS Lambda entry point for the Function URL streaming path (Req 1.1). A thin
 * {@link RequestStreamHandler} that owns no streaming logic of its own: it delegates every
 * invocation to a {@link StreamHandler} wired with a {@link FunctionUrlResolver}, so the
 * Function URL Lambda reuses the <strong>same</strong> {@link StreamHandler} orchestration,
 * {@link S3Source}, bounded-buffer copy, and library {@link ResponseWriter} as the API Gateway
 * Lambda &mdash; differing only in event parsing (Req 1.2, 1.3). The existence-before-commit
 * ordering, status mapping, and bounded-memory streaming all live in {@link StreamHandler} and
 * are not duplicated here (Req 2.5).
 *
 * <p>The {@link StreamHandler} is built through its package-private collaborator constructor
 * so the resolver is a {@link FunctionUrlResolver} (not the default {@link FileKeyResolver}),
 * with the real {@link S3Source} and the same {@link ResponseWriter} construction
 * {@link StreamHandler}'s production constructor uses &mdash; the library's Java-visible
 * {@code (Json, Integer)} constructor with {@link Json#Default} and the top-level
 * {@code OBSERVED_MAX_PRELUDE_LEN} constant, since Java cannot use Kotlin default arguments.
 *
 * <p>Java counterpart of the Kotlin example's {@code S3FunctionUrlHandler}.
 *
 * <p>Priming for SnapStart (Req 8.2): a static {@link Priming} field is eagerly instantiated
 * during class initialization so the CRaC warm-up hook registers with the global context when
 * the runtime loads this handler class, mirroring {@link StreamHandler}. Priming is a
 * self-registering singleton guarded by CRaC's own context, so registering it on this path as
 * well does not change the handler's observable behavior.
 */
public final class S3FunctionUrlHandler implements RequestStreamHandler {

    /** Eagerly instantiate Priming so it registers with CRaC during class init (SnapStart, Req 8.2). */
    @SuppressWarnings("unused")
    private static final Priming PRIMING = new Priming();

    private final StreamHandler handler = new StreamHandler(
        new FunctionUrlResolver(),
        new S3Source(),
        new ResponseWriter(Json.Default, ResponseWriterKt.OBSERVED_MAX_PRELUDE_LEN));

    @Override
    public void handleRequest(InputStream input, OutputStream output, Context context) throws IOException {
        handler.handleRequest(input, output, context);
    }
}
